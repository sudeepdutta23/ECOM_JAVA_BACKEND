package com.ecommerce.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Makes transactions the outermost advice (order 0). Every other advice, such as the audit aspect
 * (Phase 9), then runs inside the transaction of the method it wraps: an audit entry commits with
 * its action or rolls back with it. Spring's default order would put transactions innermost.
 *
 * <p>Declaring this replaces Spring Boot's own {@code @EnableTransactionManagement}; class-based
 * proxies stay, as Boot configures them.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
public class TransactionConfig {
}
