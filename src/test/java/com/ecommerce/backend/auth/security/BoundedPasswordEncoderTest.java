package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class BoundedPasswordEncoderTest {

    /** A delegate that blocks until released and records the highest concurrency it saw. */
    static final class SlowEncoder implements PasswordEncoder {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        @Override
        public String encode(CharSequence raw) {
            return "hash";
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return true;
        }
    }

    @Test
    void neverRunsMoreHashesThanPermitted() throws Exception {
        SlowEncoder slow = new SlowEncoder();
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(slow, 2, Duration.ofSeconds(5));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        Future<?>[] calls = new Future<?>[6];
        for (int i = 0; i < 6; i++) {
            calls[i] = pool.submit(() -> bounded.matches("pw", "hash"));
        }
        slow.entered.await(5, TimeUnit.SECONDS);
        Thread.sleep(200); // give the other threads time to try
        slow.release.countDown();
        for (Future<?> call : calls) {
            call.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(slow.maxRunning.get()).isEqualTo(2);
    }

    @Test
    void failsFastWith503WhenSaturated() throws Exception {
        SlowEncoder slow = new SlowEncoder();
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(slow, 1, Duration.ofMillis(100));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holder = pool.submit(() -> bounded.matches("pw", "hash"));
        slow.entered.await(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> bounded.matches("pw", "hash"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        slow.release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    void permitsAreReturnedEvenWhenTheDelegateThrows() {
        PasswordEncoder failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return false;
            }
        };
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(failing, 1, Duration.ofMillis(100));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> bounded.encode("pw")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(bounded.matches("pw", "hash")).isFalse(); // the single permit is still available
    }
}
