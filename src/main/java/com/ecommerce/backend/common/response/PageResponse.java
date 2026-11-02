package com.ecommerce.backend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * One page of an offset-paginated list (ADR-011), carried inside {@link ApiResponse}.
 *
 * <p>A dedicated record instead of serialising Spring's {@code Page}: that type's JSON shape is an
 * implementation detail (Spring Data itself warns against exposing it), so the API contract would
 * change whenever the library does.
 */
@Schema(description = "One page of results")
public record PageResponse<T>(
        List<T> content,
        @Schema(example = "0", description = "Zero-based page index") int page,
        @Schema(example = "20") int size,
        @Schema(example = "137") long totalElements,
        @Schema(example = "7") int totalPages) {

    /** ADR-011 limits, shared by every offset-paginated endpoint. */
    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;
    public static final String DEFAULT_SIZE = "20";

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
