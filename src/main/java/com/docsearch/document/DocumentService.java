package com.docsearch.document;

import com.docsearch.config.RedisCacheConfig;
import com.docsearch.document.dto.DocumentRequest;
import com.docsearch.document.dto.DocumentResponse;
import com.docsearch.exception.DocumentNotFoundException;
import com.docsearch.search.IndexingService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

	private final DocumentRepository repository;
	private final IndexingService indexingService;

	public DocumentService(DocumentRepository repository, IndexingService indexingService) {
		this.repository = repository;
		this.indexingService = indexingService;
	}

	@Transactional
	public DocumentResponse create(String tenantId, DocumentRequest req) {
		Document doc = new Document(tenantId, req.title(), req.content(), req.author(), req.tags());
		repository.save(doc);
		// In production, push to a queue (Kafka/SQS) and index asynchronously.
		// For the prototype we index synchronously so search is read-after-write.
		indexingService.index(doc);
		return DocumentResponse.from(doc);
	}

	@Cacheable(cacheNames = RedisCacheConfig.DOCUMENT_CACHE,
			key = "#tenantId + ':' + #id",
			unless = "#result == null")
	public DocumentResponse get(String tenantId, String id) {
		Document doc = repository.findByIdAndTenantId(id, tenantId)
				.orElseThrow(() -> new DocumentNotFoundException(id));
		return DocumentResponse.from(doc);
	}

	@Transactional
	@CacheEvict(cacheNames = RedisCacheConfig.DOCUMENT_CACHE, key = "#tenantId + ':' + #id")
	public void delete(String tenantId, String id) {
		long deleted = repository.deleteByIdAndTenantId(id, tenantId);
		if (deleted == 0) {
			throw new DocumentNotFoundException(id);
		}
		indexingService.delete(tenantId, id);
	}
}
