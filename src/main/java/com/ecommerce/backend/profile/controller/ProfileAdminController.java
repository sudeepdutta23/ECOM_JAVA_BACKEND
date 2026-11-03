package com.ecommerce.backend.profile.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.service.ProfileReader;
import com.ecommerce.backend.rbac.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Support staff reading a customer's personal data. Read only: staff never edit someone's profile. */
@RestController
@RequestMapping(path = "/api/v1/admin/users/{userId}/profile", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: users")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProfileAdminController {

    private final ProfileReader profiles;

    public ProfileAdminController(ProfileReader profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.PROFILE_READ + "')")
    @Operation(summary = "A customer's profile and addresses",
            description = "Needs profile:read. Every access is logged with your ID. 404 for unknown and erased accounts.")
    public ApiResponse<PersonalProfile> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponse.success("Profile", profiles.readForSupport(UUID.fromString(jwt.getSubject()), userId));
    }
}
