package com.deepwistom.atoms.video;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.common.DatabaseTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * Reserves budget before a paid provider call. The reservation is a configured allowance per
 * task, not a verified provider quote. Submitted requests retain their reservation on failure
 * or cancellation until billing can be reconciled; only unsubmitted reservations are released.
 */
@Service
public class BudgetService {
    private final JdbcTemplate jdbc;
    private final BudgetProperties properties;

    public BudgetService(JdbcTemplate jdbc, BudgetProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Reservation(String id, BigDecimal amount) {}

    /** Throws {@code BUDGET_EXCEEDED} when the global ceiling would be crossed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(String runId, String modelKey) {
        return reserve(runId, modelKey, properties.videoReserveCny());
    }

    /** Same guard for text calls (web generation); the amount is the configured text reservation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveText(String runId, String modelKey) {
        return reserve(runId, modelKey, properties.textReserveCny());
    }

    private Reservation reserve(String runId, String modelKey, BigDecimal amount) {
        BigDecimal limit = properties.limitCny();
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(503, "BUDGET_NOT_CONFIGURED",
                    "单次预算预留必须大于 0，请检查服务端预算配置。");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidPrecision) {
            throw new ApiException(503, "BUDGET_NOT_CONFIGURED",
                    "单次预算预留必须能以人民币分精确表示，请检查服务端预算配置。");
        }
        if (limit.signum() <= 0) {
            throw new ApiException(403, "BUDGET_DISABLED",
                    "预算上限为 0，已按约定拒绝一切付费调用。请在服务端提高 ATOMS_BUDGET_LIMIT_CNY 后重试。");
        }
        // A locking read precedes the first consistent read, so a waiting transaction observes
        // the reservation committed by its predecessor even under MySQL REPEATABLE READ.
        jdbc.queryForObject("SELECT id FROM provider_budget_lock WHERE id=1 FOR UPDATE", Integer.class);
        BigDecimal committed = committed();
        if (committed.add(amount).compareTo(limit) > 0) {
            throw new ApiException(429, "BUDGET_EXCEEDED",
                    "预算不足以再发起生成（全局上限 " + limit.stripTrailingZeros().toPlainString()
                            + " 元，已占用 " + committed.stripTrailingZeros().toPlainString()
                            + " 元，单条预留 " + amount.stripTrailingZeros().toPlainString() + " 元）。", true);
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO provider_spend (id,run_id,model_key,reserved_cny,state,submitted,created_at,updated_at)
                VALUES (?,?,?,?,?,0,?,?)
                """, id, runId, modelKey, amount, "RESERVED", DatabaseTime.write(now), DatabaseTime.write(now));
        return new Reservation(id, amount);
    }

    @Transactional
    public void settle(String runId) {
        transition(runId, "SETTLED");
    }

    @Transactional
    public void release(String runId) {
        jdbc.update("""
                UPDATE provider_spend SET state='RELEASED',updated_at=?
                WHERE run_id=? AND state='RESERVED' AND submitted=0
                """, DatabaseTime.write(Instant.now().truncatedTo(ChronoUnit.MICROS)), runId);
    }

    /** Must commit immediately before the first paid request, outside its network operation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(String runId) {
        int matched = jdbc.update("""
                UPDATE provider_spend SET submitted=1,updated_at=? WHERE run_id=? AND state='RESERVED'
                """, DatabaseTime.write(Instant.now().truncatedTo(ChronoUnit.MICROS)), runId);
        if (matched != 1) {
            throw new ApiException(409, "BUDGET_RESERVATION_REQUIRED",
                    "任务没有有效预算预留，已阻止模型调用。");
        }
    }

    private void transition(String runId, String state) {
        jdbc.update("""
                UPDATE provider_spend SET state=?,updated_at=? WHERE run_id=? AND state='RESERVED'
                """, state, DatabaseTime.write(Instant.now().truncatedTo(ChronoUnit.MICROS)), runId);
    }

    private BigDecimal committed() {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(reserved_cny),0) FROM provider_spend WHERE state IN ('RESERVED','SETTLED')",
                BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
