package com.ecommerce.backend.catalog.controller;

import com.ecommerce.backend.catalog.dto.ProductImageResponse;
import com.ecommerce.backend.catalog.service.ProductImageService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** A seller's product images. Uploads are re-encoded; only JPEG and PNG, at most 5 MB. */
@RestController
@RequestMapping(path = "/api/v1/organizations/{orgId}/products/{productId}/images")
@Tag(name = "Seller catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProductImageController {

    private final ProductImageService images;

    public ProductImageController(ProductImageService images) {
        this.images = images;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload a product image (multipart field \"file\")",
            description = "JPEG or PNG, detected from the bytes; at most 5 MB and 6000 px a side. The stored image is "
                    + "re-encoded without metadata. 409 CATALOG_IMAGE_LIMIT at 8 images, 413 PAYLOAD_TOO_LARGE.")
    public ResponseEntity<ApiResponse<ProductImageResponse>> upload(@AuthenticationPrincipal Jwt jwt,
                                                                    @PathVariable UUID orgId, @PathVariable UUID productId,
                                                                    @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw RequestValidationException.of("file", "must not be empty");
        }
        ProductImageResponse created = images.upload(callerId(jwt), orgId, productId, bytes(file));
        return ResponseEntity.created(URI.create(created.url())).body(ApiResponse.success("Image uploaded", created));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The product's images, in upload order")
    public ApiResponse<List<ProductImageResponse>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                        @PathVariable UUID productId) {
        return ApiResponse.success("Images", images.list(callerId(jwt), orgId, productId));
    }

    @GetMapping("/{imageId}/content")
    @Operation(summary = "Preview an image, also of a draft product")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                          @PathVariable UUID productId, @PathVariable UUID imageId) {
        ProductImageService.Content content = images.sellerContent(callerId(jwt), orgId, productId, imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore()).body(content.bytes());
    }

    @DeleteMapping("/{imageId}")
    @Operation(summary = "Delete an image")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                       @PathVariable UUID productId, @PathVariable UUID imageId) {
        images.delete(callerId(jwt), orgId, productId, imageId);
        return ResponseEntity.noContent().build();
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
