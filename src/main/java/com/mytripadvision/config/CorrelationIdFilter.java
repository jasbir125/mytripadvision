package com.mytripadvision.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HEADER);
        String correlationId = Correlation.isValid(incoming) ? incoming : UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, correlationId);
        // short id of this request: printed in every log line and returned to the web page as request_id
        MDC.put(Correlation.REQUEST_MDC_KEY, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
            MDC.remove(Correlation.REQUEST_MDC_KEY);
        }
    }
}
