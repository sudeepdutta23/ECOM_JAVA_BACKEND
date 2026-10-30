package com.ecommerce.backend;

import com.ecommerce.backend.support.TestMailConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for every integration test.
 *
 * <p>Spring caches an application context per unique configuration. Because every IT extends
 * this class with identical annotations, they all share ONE context and ONE PostgreSQL
 * container, instead of starting a new database per test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}
