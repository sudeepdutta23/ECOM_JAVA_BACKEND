package com.ecommerce.backend.catalog.controller;

import com.ecommerce.backend.catalog.dto.CategoryResponse;
import com.ecommerce.backend.catalog.dto.CreateCategoryRequest;
import com.ecommerce.backend.catalog.dto.UpdateCategoryRequest;
import com.ecommerce.backend.catalog.service.CategoryService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.rbac.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff curating the category list. Reading it is public (StorefrontController). */
@RestController
@RequestMapping(path = "/api/v1/admin/categories", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasAuthority('" + Permissions.CATALOG_MANAGE + "')")
public class CategoryAdminController {

    private final CategoryService categories;

    public CategoryAdminController(CategoryService categories) {
        this.categories = categories;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a category", description = "Needs catalog:manage. 409 CATALOG_CATEGORY_SLUG_TAKEN.")
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                                @Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = categories.create(callerId(jwt), request.slug(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/catalog/categories"))
                .body(ApiResponse.success("Category created", created));
    }

    @PutMapping(path = "/{categoryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename a category (the slug never changes)")
    public ApiResponse<CategoryResponse> rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId,
                                                @Valid @RequestBody UpdateCategoryRequest request) {
        return ApiResponse.success("Category renamed",
                categories.rename(callerId(jwt), categoryId, request.name(), request.version()));
    }

    @DeleteMapping("/{categoryId}")
    @Operation(summary = "Delete an unused category", description = "409 CATALOG_CATEGORY_IN_USE while products use it.")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID categoryId) {
        categories.delete(callerId(jwt), categoryId);
        return ResponseEntity.noContent().build();
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
