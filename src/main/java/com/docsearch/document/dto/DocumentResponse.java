package com.docsearch.document.dto;

import com.docsearch.document.Document;
import java.io.Serializable;
import java.time.Instant;

public record DocumentResponse(
		String id,
		String tenantId,
		String title,
		String content,
		String author,
		String tags,
		Instant createdAt,
		Instant updatedAt
) implements Serializable {

	public static DocumentResponse from(Document d) {
		return new DocumentResponse(
				d.getId(),
				d.getTenantId(),
				d.getTitle(),
				d.getContent(),
				d.getAuthor(),
				d.getTags(),
				d.getCreatedAt(),
				d.getUpdatedAt()
		);
	}
}
