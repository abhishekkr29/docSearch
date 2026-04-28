package com.docsearch.search.dto;

import java.io.Serializable;
import java.util.List;

public record SearchHit(
		String id,
		String title,
		String author,
		String tags,
		double score,
		List<String> highlights
) implements Serializable {}
