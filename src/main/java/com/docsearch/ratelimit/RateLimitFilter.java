package com.docsearch.ratelimit;

import com.docsearch.exception.RateLimitExceededException;
import com.docsearch.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Per-tenant rate limit. Applied AFTER TenantFilter so that the tenant id
 * is already on the context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimitService rateLimitService;
	private final HandlerExceptionResolver resolver;

	public RateLimitFilter(RateLimitService rateLimitService,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		this.rateLimitService = rateLimitService;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String tenant = TenantContext.get();
		if (tenant == null) {
			// no tenant => public path (skip rate limit)
			chain.doFilter(request, response);
			return;
		}

		RateLimitService.Result result = rateLimitService.acquire(tenant);
		response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitService.capacity()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
		response.setHeader("X-RateLimit-Reset", String.valueOf(result.retryAfterSeconds()));

		if (!result.allowed()) {
			resolver.resolveException(request, response, null,
					new RateLimitExceededException(tenant, result.retryAfterSeconds()));
			return;
		}

		chain.doFilter(request, response);
	}
}
