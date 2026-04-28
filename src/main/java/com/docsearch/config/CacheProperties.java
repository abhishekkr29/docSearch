package com.docsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docsearch.cache")
public record CacheProperties(
		long searchTtlSeconds,
		long documentTtlSeconds
) {}
