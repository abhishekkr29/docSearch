package com.docsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docsearch.rate-limit")
public record RateLimitProperties(
		boolean enabled,
		long capacity,
		long windowSeconds
) {}
