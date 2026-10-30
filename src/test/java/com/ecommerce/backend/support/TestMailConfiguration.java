package com.ecommerce.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real email transport in integration tests.
 *
 * <p>{@link RecordingEmailSender} implements {@code EmailSender}, and {@code @Primary} makes it
 * win over the bean from {@code MailConfig} wherever an {@code EmailSender} is injected.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    @Bean
    @Primary
    public RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
