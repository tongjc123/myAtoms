package com.deepwistom.atoms.common;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ApiExceptionHandlerTest {
    private static final String PRIVATE_DETAIL = "private-database-and-confidential-prompt";

    static Stream<RuntimeException> storageFailures() {
        return Stream.of(
                new DataAccessResourceFailureException(PRIVATE_DETAIL),
                new CannotCreateTransactionException(PRIVATE_DETAIL),
                new TransactionSystemException(PRIVATE_DETAIL));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void databaseAndTransactionFailuresUseThePrivateRetryableErrorContract(RuntimeException failure) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController(failure))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(get("/api/projects")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        Map<String, Object> body = JsonPath.read(response.getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("code", "message", "retryable", "requestId")
                .containsEntry("code", "STORAGE_UNAVAILABLE")
                .containsEntry("retryable", true);
        assertThat(body.get("requestId")).isInstanceOf(String.class);
        assertThat((String) body.get("requestId")).matches("[0-9a-f-]{36}");
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_DETAIL,
                failure.getClass().getSimpleName(), "stackTrace");
    }

    @TestComponent
    @RestController
    static class FailingController {
        private final RuntimeException failure;

        FailingController(RuntimeException failure) { this.failure = failure; }

        @GetMapping("/api/projects")
        Object fail() { throw failure; }
    }
}
