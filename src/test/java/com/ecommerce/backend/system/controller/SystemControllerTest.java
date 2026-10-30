package com.ecommerce.backend.system.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.common.web.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: loads only MVC infrastructure, this controller, the controller advice,
 * servlet filters and our real security rules. No database, so it runs in milliseconds.
 *
 * <p>{@code @WebMvcTest} does not scan {@code @Configuration} classes, so without importing
 * {@link SecurityConfig} Spring Boot's default "everything needs a login" setup would apply.
 * The JWT decoder is mocked because this slice never verifies a real token.
 */
@WebMvcTest(SystemController.class)
@Import({SecurityConfig.class, SystemControllerTest.FixedClockConfig.class})
class SystemControllerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock testClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingIsPublicAndReturnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/system/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Service is running"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("ecommerce-backend"))
                .andExpect(jsonPath("$.data.serverTime").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void methodsNotListedAsPublicRequireAuthentication() throws Exception {
        // Only GET /ping is public. Default deny means POST is rejected before routing.
        mockMvc.perform(post("/api/v1/system/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").exists());
    }
}
