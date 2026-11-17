package com.deepwistom.atoms.project;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.common.DatabaseTime;
import com.deepwistom.atoms.session.SessionRepository;
import com.deepwistom.atoms.session.SessionService;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.deepwistom.atoms.project.ProjectModels.MutationResponse;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final SessionRepository sessions;
    public IdempotencyService(JdbcTemplate jdbc, SessionRepository sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }
    private record Receipt(String hash, int status, String body, String projectId) {}

    @Transactional
    public MutationResponse execute(String owner, String key, String command, Supplier<MutationResponse> action) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw ApiException.invalid("请求缺少有效的防重复标识，请刷新后重试。");
        }
        var session = sessions.lock(owner).orElseThrow(() ->
                new ApiException(401, "SESSION_REQUIRED", "匿名会话不可用，请重新连接。"));
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(401, "SESSION_EXPIRED", "匿名会话已过期，请重新连接。");
        }
        String hash = SessionService.sha256(command);
        var receipts = jdbc.query("""
                SELECT request_hash,response_status,response_body,project_id FROM idempotency_requests
                WHERE owner_session_id=? AND request_key=?
                """, (row, number) -> new Receipt(row.getString(1), row.getInt(2), row.getString(3), row.getString(4)), owner, key);
        if (!receipts.isEmpty()) {
            Receipt receipt = receipts.getFirst();
            if (!receipt.hash().equals(hash)) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该请求标识已用于其他操作，请重新提交。");
            }
            if (receipt.status() != 204) {
                Long accessible = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM projects WHERE id=? AND owner_session_id=? AND deleted_at IS NULL
                        """, Long.class, receipt.projectId(), owner);
                if (accessible == null || accessible == 0) {
                    throw new ApiException(404, "PROJECT_NOT_FOUND", "项目已删除，不能重新读取或创建。");
                }
            }
            return new MutationResponse(receipt.status(), receipt.body(), receipt.projectId());
        }
        MutationResponse result = action.get();
        jdbc.update("""
                INSERT INTO idempotency_requests
                (owner_session_id,request_key,request_hash,response_status,response_body,created_at,project_id)
                VALUES (?,?,?,?,?,?,?)
                """, owner, key, hash, result.status(), result.body(), DatabaseTime.write(Instant.now()), result.projectId());
        return result;
    }
}
