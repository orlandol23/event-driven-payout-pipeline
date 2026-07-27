package io.github.orlandol23.payout.api.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a correlation id on every request before anything else runs.
 *
 * <p>Ordered first so that a request rejected early, by a future authentication
 * filter for example, is still logged with an id. Runs as a filter rather than
 * an interceptor for the same reason: interceptors sit behind the filter chain
 * and would miss those requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        // Echoed back so a caller can quote the id in a bug report without
        // having to generate one themselves.
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Mandatory, not tidiness. Tomcat pools threads, so a leaked MDC
            // entry would tag an unrelated later request with this id.
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
