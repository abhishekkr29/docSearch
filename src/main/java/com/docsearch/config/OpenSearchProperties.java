package com.docsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docsearch.opensearch")
public record OpenSearchProperties(
		String host,
		int port,
		String scheme,
		String username,
		String password,
		String indexPrefix,
		int shards,
		int replicas
) {}
