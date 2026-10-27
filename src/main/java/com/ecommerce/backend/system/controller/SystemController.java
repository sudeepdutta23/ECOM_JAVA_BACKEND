package com.ecommerce.backend.system.controller;

import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.system.dto.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight, public, versioned endpoint for API clients.
 *
 * <p>Infrastructure (load balancers, Kubernetes) should use {@code /actuator/health/liveness}
 * and {@code /actuator/health/readiness} instead. This endpoint exists so client developers
 * can confirm connectivity and see the standard response envelope and request ID.
 * It deliberately does NOT touch the database.
 */
@RestController
@RequestMapping(path = "/api/v1/system", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "System", description = "Connectivity and service information")
public class SystemController {

    private final String applicationName;
    private final Clock clock;

    public SystemController(@Value("${spring.application.name}") String applicationName, Clock clock) {
        this.applicationName = applicationName;
        this.clock = clock;
    }

    @GetMapping("/ping")
    @Operation(summary = "Check that the API is reachable",
            description = "Public. Returns 200 with server time. Does not check dependencies.")
    public ApiResponse<PingResponse> ping() {
        PingResponse payload = new PingResponse("UP", applicationName, Instant.now(clock));
        return ApiResponse.success("Service is running", payload);
    }
}
