package com.docsearch.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentRequest(
		@NotBlank @Size(max = 512) String title,
		@NotBlank @Size(max = 1_000_000) String content,
		@Size(max = 128) String author,
		@Size(max = 1024) String tags
) {}
