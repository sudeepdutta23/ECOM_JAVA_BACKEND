package com.ecommerce.backend.profile.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.profile.dto.AddressRequest;
import com.ecommerce.backend.profile.dto.AddressResponse;
import com.ecommerce.backend.profile.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's address book. The owner always comes from the token; an address ID of
 * someone else is answered with 404, like an unknown one.
 */
@RestController
@RequestMapping(path = "/api/v1/profile/addresses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Profile")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AddressController {

    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    @Operation(summary = "My addresses, newest first")
    public ApiResponse<List<AddressResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Addresses", addresses.list(callerId(jwt)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save a new address",
            description = "The first address becomes the default for shipping and billing.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location header"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED, including country rules (postal code, region)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "PROFILE_ADDRESS_LIMIT")})
    public ResponseEntity<ApiResponse<AddressResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                               @Valid @RequestBody AddressRequest request) {
        AddressResponse created = addresses.create(callerId(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/profile/addresses/" + created.id()))
                .body(ApiResponse.success("Address created", created));
    }

    @GetMapping("/{addressId}")
    @Operation(summary = "One of my addresses", description = "404 RESOURCE_NOT_FOUND if it is not yours.")
    public ApiResponse<AddressResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId) {
        return ApiResponse.success("Address", addresses.get(callerId(jwt), addressId));
    }

    @PutMapping(path = "/{addressId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace one of my addresses, default flags included",
            description = "Send the version you read. Leaving a default flag out unsets it.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Replaced"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONCURRENT_MODIFICATION")})
    public ApiResponse<AddressResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId,
                                               @Valid @RequestBody AddressRequest request) {
        return ApiResponse.success("Address updated", addresses.update(callerId(jwt), addressId, request));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Delete one of my addresses",
            description = "Past orders keep their own copy. A deleted default leaves no default of that kind.")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId) {
        addresses.delete(callerId(jwt), addressId);
        return ResponseEntity.noContent().build();
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
