package com.docsearch.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docsearch.tenancy")
public record TenancyProperties(
		String header,
		boolean requireHeader,
		List<String> publicPaths
) {}
