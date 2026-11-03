package com.ecommerce.backend.profile.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.profile.dto.ProfileResponse;
import com.ecommerce.backend.profile.dto.UpdateProfileRequest;
import com.ecommerce.backend.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's own profile. Any signed-in account may use it, so there is no
 * {@code @PreAuthorize}; the account always comes from the token, never from the request.
 */
@RestController
@RequestMapping(path = "/api/v1/profile", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Profile")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "My profile", description = "Empty, without a version, until the first save.")
    public ApiResponse<ProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Profile", profiles.get(callerId(jwt)));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save my profile (replaces every field)",
            description = "A field you leave out is cleared. Send the version you read; leave it out for the first save.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Saved"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED, including an unknown time zone"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "UNAUTHORIZED, also when the account was deactivated or erased meanwhile"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONCURRENT_MODIFICATION: saved meanwhile elsewhere; reload")})
    public ApiResponse<ProfileResponse> save(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success("Profile saved", profiles.save(callerId(jwt), request));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
