package com.docsearch.search;

import com.docsearch.config.RedisCacheConfig;
import com.docsearch.search.dto.SearchHit;
import com.docsearch.search.dto.SearchResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Read-side search. Cached at the (tenant, query, page, size) tuple in Redis;
 * cache TTL is short (default 60s) so freshness is preserved even with the
 * sync indexing path used by the prototype.
 */
@Service
public class SearchService {

	private final OpenSearchClient client;
	private final IndexingService indexingService;

	public SearchService(OpenSearchClient client, IndexingService indexingService) {
		this.client = client;
		this.indexingService = indexingService;
	}

	@Cacheable(cacheNames = RedisCacheConfig.SEARCH_CACHE,
			key = "#tenantId + ':' + #query + ':' + #page + ':' + #size",
			unless = "#result == null || #result.totalHits == 0")
	public SearchResponse search(String tenantId, String query, int page, int size) {
		String index = indexingService.indexFor(tenantId);
		long start = System.currentTimeMillis();

		Query multiMatch = Query.of(q -> q.multiMatch(m -> m
				.query(query)
				.fields("title^3", "content", "tags^2", "author")
				.fuzziness("AUTO")
				.operator(Operator.Or)));

		HighlightField titleHl = HighlightField.of(b -> b);
		HighlightField contentHl = HighlightField.of(b -> b.numberOfFragments(2).fragmentSize(150));

		SearchRequest req = SearchRequest.of(b -> b
				.index(index)
				.query(multiMatch)
				.from(page * size)
				.size(size)
				.highlight(h -> h
						.preTags("<em>").postTags("</em>")
						.fields("title", titleHl)
						.fields("content", contentHl))
				.trackTotalHits(t -> t.enabled(true)));

		try {
			var rsp = client.search(req, Map.class);
			List<SearchHit> hits = new ArrayList<>(rsp.hits().hits().size());
			for (var h : rsp.hits().hits()) {
				Map<?, ?> src = h.source();
				List<String> highlights = new ArrayList<>();
				if (h.highlight() != null) {
					h.highlight().forEach((field, frags) -> highlights.addAll(frags));
				}
				hits.add(new SearchHit(
						h.id(),
						src == null ? null : (String) src.get("title"),
						src == null ? null : (String) src.get("author"),
						src == null ? null : tagsAsString(src.get("tags")),
						h.score() == null ? 0.0 : h.score(),
						highlights
				));
			}
			long total = rsp.hits().total() == null ? 0 : rsp.hits().total().value();
			long took = System.currentTimeMillis() - start;
			return new SearchResponse(query, total, page, size, took, hits);
		} catch (IOException e) {
			throw new RuntimeException("Search failed", e);
		}
	}

	@SuppressWarnings("unchecked")
	private String tagsAsString(Object v) {
		if (v == null) return null;
		if (v instanceof String s) return s;
		if (v instanceof List<?> l) return String.join(",", (List<String>) l);
		return v.toString();
	}
}
