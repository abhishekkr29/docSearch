package com.docsearch.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents", indexes = {
		@Index(name = "idx_documents_tenant", columnList = "tenant_id"),
		@Index(name = "idx_documents_tenant_created", columnList = "tenant_id, created_at")
})
public class Document {

	@Id
	@Column(name = "id", nullable = false, updatable = false, length = 64)
	private String id;

	@Column(name = "tenant_id", nullable = false, length = 64)
	private String tenantId;

	@Column(name = "title", nullable = false, length = 512)
	private String title;

	@Column(name = "content", nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(name = "author", length = 128)
	private String author;

	@Column(name = "tags", length = 1024)
	private String tags;  // comma-separated for simplicity

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Document() {}

	public Document(String tenantId, String title, String content, String author, String tags) {
		this.id = UUID.randomUUID().toString();
		this.tenantId = tenantId;
		this.title = title;
		this.content = content;
		this.author = author;
		this.tags = tags;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public String getId() { return id; }
	public String getTenantId() { return tenantId; }
	public String getTitle() { return title; }
	public String getContent() { return content; }
	public String getAuthor() { return author; }
	public String getTags() { return tags; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }

	public void setTitle(String title) { this.title = title; this.updatedAt = Instant.now(); }
	public void setContent(String content) { this.content = content; this.updatedAt = Instant.now(); }
	public void setAuthor(String author) { this.author = author; }
	public void setTags(String tags) { this.tags = tags; }
}
