package com.deepwistom.atoms;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.video.BudgetProperties;
import com.deepwistom.atoms.video.BudgetService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL lock contention and conservative settlement; never invokes a model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("integration")
class BudgetReservationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<String> runIds = new ArrayList<>();
    private String owner;
    private String project;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() {
        transaction = new TransactionTemplate(transactions);
        owner = UUID.randomUUID().toString();
        project = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO anonymous_sessions(id,secret_hash,created_at,expires_at)
                VALUES (?,?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY))
                """, owner, owner.replace("-", "") + project.replace("-", ""));
        jdbc.update("""
                INSERT INTO projects(id,owner_session_id,name,type,prompt,mode,revision,created_at,updated_at)
                VALUES (?,?,'预算锁测试','WEB','不调用模型','BUILD',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, project, owner);
    }

    @AfterEach
    void cleanup() {
        for (String run : runIds) jdbc.update("DELETE FROM provider_spend WHERE run_id=?", run);
        jdbc.update("DELETE FROM runs WHERE project_id=?", project);
        jdbc.update("DELETE FROM projects WHERE id=?", project);
        jdbc.update("DELETE FROM anonymous_sessions WHERE id=?", owner);
    }

    @Test
    void concurrentReservationsCannotBothSpendTheLastUnit() throws Exception {
        BudgetService budget = service(BigDecimal.ONE);
        String first = run();
        String second = run();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> reserveAfter(start, ready, budget, first));
            var b = executor.submit(() -> reserveAfter(start, ready, budget, second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            // Hold the real singleton lock so the test proves both reservations wait for it.
            transaction.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM provider_budget_lock WHERE id=1 FOR UPDATE", Integer.class);
                start.countDown();
                assertThatThrownBy(() -> a.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> b.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            });
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("reserved", "BUDGET_EXCEEDED");
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_spend WHERE run_id IN (?,?) AND state='RESERVED'
                """, Integer.class, first, second)).isEqualTo(1);
    }

    @Test
    void releaseRetainsAttemptedCallsButFreesUnsubmittedReservations() {
        BudgetService budget = service(new BigDecimal("3"));
        String submitted = run();
        String unsubmitted = run();
        transaction.executeWithoutResult(s -> budget.reserveText(submitted, "test-model"));
        transaction.executeWithoutResult(s -> budget.markSubmitted(submitted));
        transaction.executeWithoutResult(s -> budget.release(submitted));
        assertThat(state(submitted)).isEqualTo("RESERVED");
        transaction.executeWithoutResult(s -> budget.reserveText(unsubmitted, "test-model"));
        transaction.executeWithoutResult(s -> budget.release(unsubmitted));
        assertThat(state(unsubmitted)).isEqualTo("RELEASED");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> budget.markSubmitted(unsubmitted)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BUDGET_RESERVATION_REQUIRED"));
        transaction.executeWithoutResult(s -> budget.settle(submitted));
        assertThat(state(submitted)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT reserved_cny FROM provider_spend WHERE run_id=?",
                BigDecimal.class, submitted)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void submittedAndSettledReservationsContinueToConsumeCapacity() {
        BudgetService budget = service(BigDecimal.ONE);
        String submitted = run();
        String next = run();
        transaction.executeWithoutResult(s -> budget.reserveText(submitted, "test-model"));
        transaction.executeWithoutResult(s -> budget.markSubmitted(submitted));
        transaction.executeWithoutResult(s -> budget.release(submitted));
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> budget.reserveText(next, "test-model")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BUDGET_EXCEEDED"));
        transaction.executeWithoutResult(s -> budget.settle(submitted));
        transaction.executeWithoutResult(s -> budget.release(submitted));
        assertThat(state(submitted)).isEqualTo("SETTLED");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> budget.reserve(next, "test-model")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("BUDGET_EXCEEDED"));
    }

    @Test
    void invalidReservationCannotBypassTheLimit() {
        String run = run();
        for (BigDecimal amount : new BigDecimal[] {
                null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("0.001"), new BigDecimal("0.009")}) {
            BudgetService budget = new BudgetService(jdbc, new BudgetProperties(
                    new BigDecimal("300"), amount, amount));
            assertThatThrownBy(() -> transaction.executeWithoutResult(s -> budget.reserveText(run, "test-model")))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("BUDGET_NOT_CONFIGURED"));
            assertThatThrownBy(() -> transaction.executeWithoutResult(s -> budget.reserve(run, "test-model")))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("BUDGET_NOT_CONFIGURED"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_spend WHERE run_id=?", Integer.class, run))
                .isZero();
    }

    private String reserveAfter(CountDownLatch start, CountDownLatch ready, BudgetService budget, String run)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            transaction.executeWithoutResult(s -> budget.reserveText(run, "test-model"));
            return "reserved";
        } catch (ApiException error) {
            return error.code();
        }
    }

    private BudgetService service(BigDecimal available) {
        BigDecimal used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_cny),0) FROM provider_spend WHERE state IN ('RESERVED','SETTLED')
                """, BigDecimal.class);
        return new BudgetService(jdbc, new BudgetProperties(used.add(available), BigDecimal.ONE, BigDecimal.ONE));
    }

    private String state(String run) {
        return jdbc.queryForObject("SELECT state FROM provider_spend WHERE run_id=?", String.class, run);
    }

    private String run() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO runs(id,project_id,owner_session_id,purpose,status,engine,prompt,created_at)
                VALUES (?,?,?,'WEB','QUEUED','budget-test','No paid calls',UTC_TIMESTAMP(6))
                """, id, project, owner);
        runIds.add(id);
        return id;
    }
}
