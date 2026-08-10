package com.cmagent.server.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {
    @Test
    void propagatesSafeRequestIdToRequestResponseAndDiagnosticContext() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tools");
        request.addHeader(RequestCorrelationFilter.ERROR_ID_HEADER, "request-20260810");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
            assertThat(RequestCorrelationFilter.errorIdOf((MockHttpServletRequest) currentRequest))
                    .isEqualTo("request-20260810");
            assertThat(MDC.get("errorId")).isEqualTo("request-20260810");
        });

        assertThat(response.getHeader(RequestCorrelationFilter.ERROR_ID_HEADER)).isEqualTo("request-20260810");
        assertThat(MDC.get("errorId")).isNull();
    }

    @Test
    void replacesUnsafeIncomingRequestId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tools");
        request.addHeader(RequestCorrelationFilter.ERROR_ID_HEADER, "invalid request id\nsecret=value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> { });

        assertThat(response.getHeader(RequestCorrelationFilter.ERROR_ID_HEADER))
                .matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                .isNotEqualTo("invalid request id\nsecret=value");
    }
}
