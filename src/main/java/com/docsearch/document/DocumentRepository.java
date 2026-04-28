package com.docsearch.document;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {

	Optional<Document> findByIdAndTenantId(String id, String tenantId);

	long deleteByIdAndTenantId(String id, String tenantId);
}
