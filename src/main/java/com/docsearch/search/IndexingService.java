package com.docsearch.search;

import com.docsearch.config.OpenSearchProperties;
import com.docsearch.document.Document;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns OpenSearch document lifecycle: indexing, deleting, and lazy index
 * creation per tenant. Each tenant gets a dedicated index `{prefix}-{tenant}`
 * so we can isolate retention, sharding, and (later) tier the workloads.
 */
@Service
public class IndexingService {

	private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

	private final OpenSearchClient client;
	private final OpenSearchProperties props;
	private final Map<String, Boolean> ensuredIndices = new ConcurrentHashMap<>();

	public IndexingService(OpenSearchClient client, OpenSearchProperties props) {
		this.client = client;
		this.props = props;
	}

	public String indexFor(String tenantId) {
		return props.indexPrefix() + "-" + tenantId.toLowerCase();
	}

	public void index(Document doc) {
		String index = indexFor(doc.getTenantId());
		ensureIndex(index);
		try {
			client.index(req -> req
					.index(index)
					.id(doc.getId())
					.document(toIndexed(doc))
					.refresh(org.opensearch.client.opensearch._types.Refresh.WaitFor));
		} catch (IOException e) {
			throw new IndexingException("Failed to index document " + doc.getId(), e);
		}
	}

	public void delete(String tenantId, String id) {
		String index = indexFor(tenantId);
		try {
			client.delete(req -> req.index(index).id(id)
					.refresh(org.opensearch.client.opensearch._types.Refresh.WaitFor));
		} catch (IOException e) {
			throw new IndexingException("Failed to delete document " + id, e);
		}
	}

	private Map<String, Object> toIndexed(Document d) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", d.getId());
		m.put("tenant_id", d.getTenantId());
		m.put("title", d.getTitle());
		m.put("content", d.getContent());
		m.put("author", d.getAuthor());
		m.put("tags", d.getTags() == null ? null : d.getTags().split("\\s*,\\s*"));
		m.put("created_at", d.getCreatedAt().toString());
		m.put("updated_at", d.getUpdatedAt().toString());
		return m;
	}

	private void ensureIndex(String index) {
		if (ensuredIndices.containsKey(index)) return;
		try {
			boolean exists = client.indices().exists(b -> b.index(index)).value();
			if (!exists) {
				log.info("Creating OpenSearch index {}", index);
				Map<String, Property> mappings = new HashMap<>();
				mappings.put("id", Property.of(p -> p.keyword(k -> k)));
				mappings.put("tenant_id", Property.of(p -> p.keyword(k -> k)));
				mappings.put("title", Property.of(p -> p.text(t -> t.analyzer("standard").fields("keyword",
						Property.of(k -> k.keyword(kk -> kk.ignoreAbove(256)))))));
				mappings.put("content", Property.of(p -> p.text(t -> t.analyzer("standard"))));
				mappings.put("author", Property.of(p -> p.keyword(k -> k)));
				mappings.put("tags", Property.of(p -> p.keyword(k -> k)));
				mappings.put("created_at", Property.of(p -> p.date(d -> d)));
				mappings.put("updated_at", Property.of(p -> p.date(d -> d)));

				CreateIndexRequest req = new CreateIndexRequest.Builder()
						.index(index)
						.settings(IndexSettings.of(s -> s
								.numberOfShards(String.valueOf(props.shards()))
								.numberOfReplicas(String.valueOf(props.replicas()))))
						.mappings(TypeMapping.of(m -> m.properties(mappings)))
						.build();
				client.indices().create(req);
			}
			ensuredIndices.put(index, true);
		} catch (IOException e) {
			throw new IndexingException("Failed to ensure index " + index, e);
		}
	}

	public static class IndexingException extends RuntimeException {
		public IndexingException(String msg, Throwable cause) { super(msg, cause); }
	}
}
