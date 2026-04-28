package com.docsearch.search;

import com.docsearch.search.dto.SearchResponse;
import com.docsearch.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
@Validated
@Tag(name = "Search", description = "Full-text search across a tenant's documents")
public class SearchController {

	private final SearchService searchService;

	public SearchController(SearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping
	@Operation(summary = "Search documents",
			description = "Full-text query with relevance ranking + highlights. Tenant-scoped via X-Tenant-ID header.")
	public SearchResponse search(
			@Parameter(description = "Query string", example = "distributed systems")
			@RequestParam("q") @NotBlank String q,
			@RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(value = "size", defaultValue = "10") @Min(1) @Max(100) int size
	) {
		return searchService.search(TenantContext.require(), q, page, size);
	}
}
