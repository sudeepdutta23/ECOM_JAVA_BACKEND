package com.ecommerce.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

class AsyncConfigTest {

    private final TaskDecorator decorator = new AsyncConfig().mdcPropagatingTaskDecorator();

    @Test
    void copiesRequestIdToWorkerThreadAndCleansUp() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> seenInTask = new AtomicReference<>();
        AtomicReference<String> seenAfterTask = new AtomicReference<>("not-checked");

        MDC.put("requestId", "req-12345678");
        Runnable decorated = decorator.decorate(() -> seenInTask.set(MDC.get("requestId")));
        MDC.remove("requestId");

        pool.submit(decorated).get(5, TimeUnit.SECONDS);
        pool.submit(() -> seenAfterTask.set(MDC.get("requestId"))).get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(seenInTask.get()).isEqualTo("req-12345678");
        assertThat(seenAfterTask.get()).isNull(); // no leak into the next task on the same thread
    }
}
