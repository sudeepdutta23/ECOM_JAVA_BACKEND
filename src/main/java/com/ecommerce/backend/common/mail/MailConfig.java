package com.ecommerce.backend.common.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses the email transport.
 *
 * <p>Spring Boot creates a {@link JavaMailSender} only when {@code spring.mail.host} is set.
 * If it exists we send over SMTP; otherwise we fall back to logging, so the application still
 * starts in environments without mail.
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> javaMailSender,
                                   @Value("${app.mail.from}") String from) {
        JavaMailSender smtp = javaMailSender.getIfAvailable();
        return smtp != null ? new SmtpEmailSender(smtp, from) : new LoggingEmailSender();
    }
}
