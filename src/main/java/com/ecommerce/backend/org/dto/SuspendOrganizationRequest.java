package com.ecommerce.backend.org.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/admin/organizations/{orgId}/suspend}. */
@Schema(description = "Why the organisation is suspended")
public record SuspendOrganizationRequest(
        @Schema(example = "Counterfeit goods, case 4711", description = "Internal note; never shown to members or logged")
        @NotBlank @Size(max = 500)
        String reason) {

    /** The reason may name people; it must not reach a log through toString. */
    @Override
    public String toString() {
        return "SuspendOrganizationRequest[reason=<hidden>]";
    }
}
