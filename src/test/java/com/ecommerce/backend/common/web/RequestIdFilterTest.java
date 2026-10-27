package com.ecommerce.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        String id = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(id).isNotNull();
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void reusesSafeIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "gateway-abc-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("gateway-abc-12345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space inside", "line\nbreak-injection", "<script>alert(1)</script>"})
    void replacesUnsafeIncomingHeader(String unsafe) {
        String resolved = RequestIdFilter.resolveRequestId(unsafe);

        assertThat(resolved).isNotEqualTo(unsafe);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    void rejectsOverlongHeader() {
        String tooLong = "a".repeat(65);

        assertThat(RequestIdFilter.resolveRequestId(tooLong)).isNotEqualTo(tooLong);
    }

    @Test
    void exposesIdInMdcDuringRequestAndClearsItAfterwards() throws Exception {
        AtomicReference<String> seenInsideChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInsideChain.set(RequestIdFilter.currentRequestId());
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(seenInsideChain.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER_NAME));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
