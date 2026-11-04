package com.ecommerce.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpCachingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theEtagDependsOnTheDataOnly() {
        String etag = HttpCaching.etag(mapper, List.of(Map.of("slug", "mugs")));

        assertThat(etag).isEqualTo(HttpCaching.etag(mapper, List.of(Map.of("slug", "mugs"))));
        assertThat(etag).isNotEqualTo(HttpCaching.etag(mapper, List.of(Map.of("slug", "cups"))));
        assertThat(etag).matches("\"[A-Za-z0-9_-]{22}\"");
    }

    @Test
    void ifNoneMatchAcceptsListsWeakTagsAndTheWildcard() {
        String etag = "\"abc\"";

        assertThat(HttpCaching.matches("\"abc\"", etag)).isTrue();
        assertThat(HttpCaching.matches("\"old\", W/\"abc\"", etag)).isTrue();
        assertThat(HttpCaching.matches("*", etag)).isTrue();
        assertThat(HttpCaching.matches("\"old\"", etag)).isFalse();
        assertThat(HttpCaching.matches("abc", etag)).as("unquoted is a different tag").isFalse();
        assertThat(HttpCaching.matches(null, etag)).isFalse();
        assertThat(HttpCaching.matches(" ", etag)).isFalse();
    }
}
