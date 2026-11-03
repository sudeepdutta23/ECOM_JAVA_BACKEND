package com.ecommerce.backend.notification.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.notification.dto.NotificationResponse;
import com.ecommerce.backend.notification.dto.PreferencesRequest;
import com.ecommerce.backend.notification.dto.PreferencesResponse;
import com.ecommerce.backend.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's inbox and email preferences. */
@RestController
@RequestMapping(path = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Notifications")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "My notifications, newest first")
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Notifications", notifications.list(callerId(jwt), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many notifications I have not read", description = "For the badge; cheap to poll.")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Unread", Map.of("unread", notifications.unreadCount(callerId(jwt))));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one notification as read", description = "Idempotent; 404 if it is not yours.")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {
        notifications.markRead(callerId(jwt), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every notification as read")
    public ApiResponse<Map<String, Integer>> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("All read", Map.of("marked", notifications.markAllRead(callerId(jwt))));
    }

    @GetMapping("/preferences")
    @Operation(summary = "My email preferences", description = "Account security emails are always sent.")
    public ApiResponse<PreferencesResponse> preferences(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Preferences", notifications.preferences(callerId(jwt)));
    }

    @PutMapping(path = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Choose which optional emails I get")
    public ApiResponse<PreferencesResponse> savePreferences(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody PreferencesRequest request) {
        return ApiResponse.success("Preferences saved", notifications.savePreferences(callerId(jwt),
                request.orderUpdatesEmail(), request.sellerOrdersEmail()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
