package com.ecommerce.backend.auth.controller;

import com.ecommerce.backend.auth.dto.SessionResponse;
import com.ecommerce.backend.auth.security.CurrentUser;
import com.ecommerce.backend.auth.service.SessionService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Where am I signed in?" Sessions are resources of the signed-in user, so this part of auth is
 * modelled resource-style (GET the collection, DELETE one item), unlike the login/logout commands.
 */
@RestController
@RequestMapping(path = "/api/v1/auth/me/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Account")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "List my signed-in devices", description = "Newest activity first. `current` marks this device.")
    public ApiResponse<List<SessionResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Sessions", sessions.list(CurrentUser.id(jwt), CurrentUser.sessionId(jwt)));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Sign out one device",
            description = "That device can no longer refresh; its access token expires within 15 minutes. "
                    + "Ending the current session works like logout. 404 for unknown sessions and other users' sessions.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Ended"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public ResponseEntity<Void> end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        sessions.end(CurrentUser.id(jwt), sessionId);
        return ResponseEntity.noContent().build();
    }
}
