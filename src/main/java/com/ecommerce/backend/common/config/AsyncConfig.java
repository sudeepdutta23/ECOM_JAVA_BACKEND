package com.ecommerce.backend.common.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} methods.
 *
 * <p>Spring Boot auto-configures the thread pool ({@code spring.task.execution.*} in
 * application.yml) and applies any {@link TaskDecorator} bean to it.
 *
 * <p><b>Why the decorator?</b> The MDC (where the request ID lives) is bound to a thread. Work
 * handed to a pool thread would lose it, and logs from the async email sender could no longer be
 * matched to the request that caused them. The decorator copies the caller's MDC into the pool
 * thread and clears it afterwards, because pool threads are reused.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

    @Bean
    public TaskDecorator mdcPropagatingTaskDecorator() {
        return runnable -> {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
