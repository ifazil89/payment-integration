package com.fazil.payments.common.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in the MDC (taken from {@code X-Correlation-Id} if well-formed, so callers cannot inject
 * log content) and echoes it back on the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9\\-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(CorrelationId.HEADER);
		String correlationId = (incoming != null && SAFE_ID.matcher(incoming).matches()) ? incoming
				: UUID.randomUUID().toString();
		MDC.put(CorrelationId.MDC_KEY, correlationId);
		response.setHeader(CorrelationId.HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}

}
