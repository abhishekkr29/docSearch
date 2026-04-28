package com.docsearch.tenancy;

import com.docsearch.config.TenancyProperties;
import com.docsearch.exception.TenantRequiredException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Extracts the tenant id from the X-Tenant-ID header and binds it to the
 * thread-local TenantContext for the duration of the request. Public paths
 * (actuator, swagger) bypass the tenant requirement.
 *
 * Filter exceptions are routed through {@link HandlerExceptionResolver} so the
 * same {@code @RestControllerAdvice} that handles controller errors produces
 * the JSON error response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

	private static final Pattern VALID_TENANT = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

	private final TenancyProperties props;
	private final HandlerExceptionResolver resolver;

	public TenantFilter(TenancyProperties props,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		this.props = props;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (isPublicPath(request)) {
			chain.doFilter(request, response);
			return;
		}

		String tenant = request.getHeader(props.header());
		if (tenant == null || tenant.isBlank()) {
			if (props.requireHeader()) {
				resolver.resolveException(request, response, null, new TenantRequiredException(props.header()));
				return;
			}
		} else {
			if (!VALID_TENANT.matcher(tenant).matches()) {
				resolver.resolveException(request, response, null,
						new IllegalArgumentException("Invalid tenant id: must match [a-zA-Z0-9_-]{1,64}"));
				return;
			}
			TenantContext.set(tenant);
		}

		try {
			chain.doFilter(request, response);
		} finally {
			TenantContext.clear();
		}
	}

	private boolean isPublicPath(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (props.publicPaths() == null) return false;
		for (String pattern : props.publicPaths()) {
			if (matches(pattern, path)) return true;
		}
		return false;
	}

	private boolean matches(String pattern, String path) {
		// minimal Ant-ish matcher: trailing /** matches any subpath
		if (pattern.endsWith("/**")) {
			String prefix = pattern.substring(0, pattern.length() - 3);
			return path.startsWith(prefix);
		}
		return pattern.equals(path);
	}
}
