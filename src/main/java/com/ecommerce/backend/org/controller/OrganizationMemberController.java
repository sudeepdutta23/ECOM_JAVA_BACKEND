package com.ecommerce.backend.org.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.dto.AcceptInvitationRequest;
import com.ecommerce.backend.org.dto.ChangeMemberRoleRequest;
import com.ecommerce.backend.org.dto.InvitationResponse;
import com.ecommerce.backend.org.dto.InviteMemberRequest;
import com.ecommerce.backend.org.dto.MemberResponse;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.service.InvitationService;
import com.ecommerce.backend.org.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members and invitations of an organisation. Authorization is by organisation role, per request;
 * non-members get 404 for every {@code {orgId}}. See docs/modules/ORGANIZATIONS.md, section 8.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Organisations: members")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class OrganizationMemberController {

    private final MembershipService memberships;
    private final InvitationService invitations;

    public OrganizationMemberController(MembershipService memberships, InvitationService invitations) {
        this.memberships = memberships;
        this.invitations = invitations;
    }

    // ------------------------------------------------------------------ members

    @GetMapping("/{orgId}/members")
    @Operation(summary = "Members with their roles, longest-serving first", description = "Any member.")
    public ApiResponse<PageResponse<MemberResponse>> members(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Members", memberships.list(callerId(jwt), orgId, page, size));
    }

    @PutMapping(path = "/{orgId}/members/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change a member's role",
            description = "Needs MEMBER_MANAGE, and both the current and the new role must be below yours (owners may "
                    + "manage owners). 403 ORG_SELF_ACTION_DENIED, ORG_RANK_DENIED; 409 ORG_LAST_OWNER, ORG_INVALID_STATUS.")
    public ApiResponse<MemberResponse> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                  @PathVariable UUID userId,
                                                  @Valid @RequestBody ChangeMemberRoleRequest request) {
        return ApiResponse.success("Role changed", memberships.changeRole(callerId(jwt), orgId, userId, request.role()));
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @Operation(summary = "Remove a member",
            description = "Needs MEMBER_MANAGE and a higher rank. 403 ORG_SELF_ACTION_DENIED (use leave), ORG_RANK_DENIED; "
                    + "409 ORG_LAST_OWNER, ORG_INVALID_STATUS.")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                       @PathVariable UUID userId) {
        memberships.remove(callerId(jwt), orgId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orgId}/leave")
    @Operation(summary = "Leave the organisation",
            description = "Any member, in any status. 409 ORG_LAST_OWNER: make someone else an owner, or close it first.")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId) {
        memberships.leave(callerId(jwt), orgId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ invitations

    @PostMapping(path = "/{orgId}/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Invite an email address",
            description = "Needs MEMBER_MANAGE; the role must be below yours (owners may invite owners). Inviting the "
                    + "same address again replaces the open invitation. 409 ORG_ALREADY_MEMBER, ORG_INVITATION_LIMIT.")
    public ResponseEntity<ApiResponse<InvitationResponse>> invite(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable UUID orgId,
                                                                  @Valid @RequestBody InviteMemberRequest request) {
        InvitationResponse invitation = invitations.invite(callerId(jwt), orgId, request.email(), request.role());
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + orgId + "/invitations/" + invitation.id()))
                .body(ApiResponse.success("Invitation sent", invitation));
    }

    @GetMapping("/{orgId}/invitations")
    @Operation(summary = "Pending invitations, newest first", description = "Needs MEMBER_MANAGE.")
    public ApiResponse<PageResponse<InvitationResponse>> pending(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Invitations", invitations.pending(callerId(jwt), orgId, page, size));
    }

    @DeleteMapping("/{orgId}/invitations/{invitationId}")
    @Operation(summary = "Revoke an invitation (idempotent)", description = "Needs MEMBER_MANAGE and a higher rank.")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                       @PathVariable UUID invitationId) {
        invitations.revoke(callerId(jwt), orgId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/invitations/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept an invitation from its email link",
            description = "Sign in with the invited address first. 400 ORG_INVALID_INVITATION for an unknown, used, "
                    + "revoked or expired link, or one meant for another address. Returns the organisation.")
    public ApiResponse<OrganizationResponse> accept(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody AcceptInvitationRequest request) {
        return ApiResponse.success("Invitation accepted", invitations.accept(callerId(jwt), request.token()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
