package com.mytripadvision.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String run(String header, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(Correlation.HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(Correlation.MDC_KEY) + "/" + MDC.get(Correlation.REQUEST_MDC_KEY));
            }
        });
        return response.getHeader(Correlation.HEADER);
    }

    @Test
    void validHeader_isEchoed_andPutInTheMdc_thenRemoved() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        assertThat(run("abc-123", seen)).isEqualTo("abc-123");
        assertThat(seen.get()).matches("abc-123/[0-9a-f]{8}");
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
        assertThat(MDC.get(Correlation.REQUEST_MDC_KEY)).isNull();
    }

    @Test
    void missingOrInvalidHeader_isReplacedByAGeneratedId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        assertThat(run(null, seen)).matches("[0-9a-f-]{36}");
        assertThat(run("bad value\nwith newline", seen)).matches("[0-9a-f-]{36}");
        assertThat(run("x".repeat(65), seen)).matches("[0-9a-f-]{36}");
    }
}
