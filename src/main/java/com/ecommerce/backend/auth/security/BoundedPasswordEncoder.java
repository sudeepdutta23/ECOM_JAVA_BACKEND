package com.ecommerce.backend.auth.security;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Bulkhead around the password hasher: at most N hashes run at the same time.
 *
 * <p><b>Why:</b> Argon2id deliberately uses 19 MiB of memory and about 50 ms of CPU per hash, and
 * it is memory-bandwidth bound, so running more of them in parallel does not raise throughput
 * (measured: ~30 checks/s on 8 cores whether 8 or 32 threads run). Without a cap, a login flood
 * from many IPs could have all 200 Tomcat threads hashing at once: ~3.8 GB of memory and
 * multi-second latency for everyone.
 *
 * <p>With the cap, extra requests wait briefly for a permit and then fail fast with 503, which a
 * load balancer or client can retry. The rest of the API stays responsive.
 */
public class BoundedPasswordEncoder implements PasswordEncoder {

    private static final Logger log = LoggerFactory.getLogger(BoundedPasswordEncoder.class);

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Duration maxWait;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent, true); // fair: first come, first served
        this.maxWait = maxWait;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        acquire();
        try {
            return delegate.encode(rawPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        acquire();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            permits.release();
        }
    }

    /** Only inspects the stored hash's prefix and parameters: cheap, so not bounded. */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Password hashing saturated; request rejected after waiting {} ms", maxWait.toMillis());
                throw busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
    }

    private static BusinessException busy() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "The service is busy. Please try again in a few seconds.");
    }
}
