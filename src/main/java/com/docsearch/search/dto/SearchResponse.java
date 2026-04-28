package com.docsearch.search.dto;

import java.io.Serializable;
import java.util.List;

public record SearchResponse(
		String query,
		long totalHits,
		int page,
		int size,
		long tookMs,
		List<SearchHit> hits
) implements Serializable {}
