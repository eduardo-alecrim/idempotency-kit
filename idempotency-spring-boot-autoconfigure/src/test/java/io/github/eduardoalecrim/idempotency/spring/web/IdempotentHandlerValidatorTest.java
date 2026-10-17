// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyAutoConfiguration;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyConfigurationException;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyInMemoryStoreAutoConfiguration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class IdempotentHandlerValidatorTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyInMemoryStoreAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class,
                    IdempotencyWebMvcAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class));

    @Test
    @DisplayName("FR-16: @Idempotent on a GET handler fails startup naming the handler")
    void rejectsGet() {
        expectError(GetHandler.class, "GetHandler#handle handles GET, which is safe and cannot be idempotent");
    }

    @Test
    @DisplayName("FR-16: @Idempotent on a HEAD handler fails startup")
    void rejectsHead() {
        expectError(HeadHandler.class, "HeadHandler#handle handles HEAD");
    }

    @Test
    @DisplayName("FR-16: @Idempotent on an OPTIONS handler fails startup")
    void rejectsOptions() {
        expectError(OptionsHandler.class, "OptionsHandler#handle handles OPTIONS");
    }

    @Test
    @DisplayName("FR-16: @Idempotent on a TRACE handler fails startup")
    void rejectsTrace() {
        expectError(TraceHandler.class, "TraceHandler#handle handles TRACE");
    }

    @Test
    @DisplayName("FR-16: @Idempotent on a handler of every method fails startup")
    void rejectsUnrestrictedMappings() {
        expectError(AnyMethodHandler.class, "AnyMethodHandler#handle maps every HTTP method, including GET");
    }

    @Test
    @DisplayName("FR-16: @Idempotent on a PUT handler fails unless PUT is in idempotency.methods")
    void rejectsMethodsThatAreNotEnabled() {
        expectError(PutHandler.class, "PutHandler#handle handles PUT, which is not in idempotency.methods");
        runner.withUserConfiguration(PutHandler.class)
                .withPropertyValues("idempotency.methods=POST,PUT")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("FR-20: an SseEmitter handler fails startup")
    void rejectsSse() {
        expectError(SseHandler.class, "SseHandler#handle returns SseEmitter");
    }

    @Test
    @DisplayName("FR-20: a StreamingResponseBody handler fails startup, also inside ResponseEntity")
    void rejectsStreamingBodies() {
        expectError(StreamingHandler.class, "StreamingHandler#handle returns StreamingResponseBody");
        expectError(StreamingEntityHandler.class, "StreamingEntityHandler#handle returns StreamingResponseBody");
    }

    @Test
    @DisplayName("FR-20: a DeferredResult handler fails startup")
    void rejectsDeferredResults() {
        expectError(DeferredHandler.class, "DeferredHandler#handle returns DeferredResult");
    }

    @Test
    @DisplayName("FR-20: a Callable handler fails startup")
    void rejectsCallables() {
        expectError(CallableHandler.class, "CallableHandler#handle returns Callable");
    }

    @Test
    @DisplayName("FR-20: a CompletionStage handler fails startup")
    void rejectsCompletionStages() {
        expectError(FutureHandler.class, "FutureHandler#handle returns CompletableFuture");
    }

    @Test
    @DisplayName("a handler whose lease is not shorter than its TTL fails startup")
    void rejectsLeaseNotShorterThanTtl() {
        expectError(LeaseHandler.class, "@Idempotent on LeaseHandler#handle");
    }

    @Test
    void rejectsConfiguredRoutesOfOtherMethods() {
        runner.withPropertyValues("idempotency.routes[0].methods=GET", "idempotency.routes[0].path=/legacy")
                .run(context -> assertThat(failure(context))
                        .hasMessageContaining("idempotency.routes: GET /legacy uses GET, which is not in"));
    }

    @Test
    void reportsEveryInvalidHandlerAtOnce() {
        runner.withUserConfiguration(GetHandler.class, SseHandler.class)
                .run(context -> assertThat(failure(context))
                        .hasMessageStartingWith("Invalid idempotent operations:")
                        .hasMessageContaining("GetHandler#handle")
                        .hasMessageContaining("SseHandler#handle"));
    }

    @Test
    void startsWithValidHandlersAndLogsEachOperation() {
        List<String> logged = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(IdempotentHandlerValidator.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            runner.withUserConfiguration(ValidHandlers.class)
                    .withPropertyValues("idempotency.routes[0].path=/legacy")
                    .run(context -> assertThat(context).hasNotFailed());
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(logged)
                .containsExactlyInAnyOrder(
                        "Idempotent operation POST /orders (ttl 1d, lease 30s, key required, client scope)",
                        "Idempotent operation POST /orders/{id}/payments (ttl 1h, lease 10s, key optional, global scope)",
                        "Idempotent operation POST /legacy (ttl 1d, lease 30s, key required, client scope)");
    }

    private void expectError(Class<?> handler, String message) {
        runner.withUserConfiguration(handler)
                .run(context -> assertThat(failure(context)).hasMessageContaining(message));
    }

    private static Throwable failure(AssertableWebApplicationContext context) {
        assertThat(context).hasFailed();
        @Nullable Throwable failure = context.getStartupFailure();
        while (failure != null && !(failure instanceof IdempotencyConfigurationException)) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOf(IdempotencyConfigurationException.class);
        return Objects.requireNonNull(failure);
    }

    @RestController
    static class GetHandler {
        @Idempotent
        @GetMapping("/a")
        String handle() {
            return "";
        }
    }

    @RestController
    static class HeadHandler {
        @Idempotent
        @RequestMapping(path = "/a", method = RequestMethod.HEAD)
        String handle() {
            return "";
        }
    }

    @RestController
    static class OptionsHandler {
        @Idempotent
        @RequestMapping(path = "/a", method = RequestMethod.OPTIONS)
        String handle() {
            return "";
        }
    }

    @RestController
    static class TraceHandler {
        @Idempotent
        @RequestMapping(path = "/a", method = RequestMethod.TRACE)
        String handle() {
            return "";
        }
    }

    @RestController
    static class AnyMethodHandler {
        @Idempotent
        @RequestMapping("/a")
        String handle() {
            return "";
        }
    }

    @RestController
    static class PutHandler {
        @Idempotent
        @PutMapping("/a")
        String handle() {
            return "";
        }
    }

    @RestController
    static class SseHandler {
        @Idempotent
        @PostMapping("/a")
        SseEmitter handle() {
            return new SseEmitter();
        }
    }

    @RestController
    static class StreamingHandler {
        @Idempotent
        @PostMapping("/a")
        StreamingResponseBody handle() {
            return out -> {};
        }
    }

    @RestController
    static class StreamingEntityHandler {
        @Idempotent
        @PostMapping("/a")
        ResponseEntity<StreamingResponseBody> handle() {
            return ResponseEntity.ok(out -> {});
        }
    }

    @RestController
    static class DeferredHandler {
        @Idempotent
        @PostMapping("/a")
        DeferredResult<String> handle() {
            return new DeferredResult<>();
        }
    }

    @RestController
    static class CallableHandler {
        @Idempotent
        @PostMapping("/a")
        Callable<String> handle() {
            return () -> "";
        }
    }

    @RestController
    static class FutureHandler {
        @Idempotent
        @PostMapping("/a")
        CompletableFuture<String> handle() {
            return CompletableFuture.completedFuture("");
        }
    }

    @RestController
    static class LeaseHandler {
        @Idempotent(ttl = "10s", lease = "10s")
        @PostMapping("/a")
        String handle() {
            return "";
        }
    }

    // The class annotation applies to POST handlers only; the GET handler stays as it is.
    @RestController
    @Idempotent
    static class ValidHandlers {
        @PostMapping("/orders")
        String create() {
            return "";
        }

        @GetMapping("/orders")
        String list() {
            return "";
        }

        @Idempotent(ttl = "1h", lease = "10s", required = false, scope = Idempotent.Scope.GLOBAL)
        @PostMapping("/orders/{id}/payments")
        String pay() {
            return "";
        }

        @GetMapping("/stream")
        StreamingResponseBody stream() {
            return out -> {};
        }
    }
}
