package com.docsearch.search;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docsearch.config.RateLimitProperties;
import com.docsearch.config.TenancyProperties;
import com.docsearch.exception.GlobalExceptionHandler;
import com.docsearch.ratelimit.RateLimitFilter;
import com.docsearch.ratelimit.RateLimitService;
import com.docsearch.search.dto.SearchHit;
import com.docsearch.search.dto.SearchResponse;
import com.docsearch.tenancy.TenantFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SearchController.class)
@Import({GlobalExceptionHandler.class, TenantFilter.class, RateLimitFilter.class,
		SearchControllerWebTest.TestProps.class})
class SearchControllerWebTest {

	@Autowired MockMvc mvc;
	@MockitoBean SearchService searchService;
	@MockitoBean RateLimitService rateLimitService;

	@Test
	void rejects_request_without_tenant_header() throws Exception {
		mvc.perform(get("/search").param("q", "anything"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						org.hamcrest.Matchers.containsString("X-Tenant-ID")));
	}

	@Test
	void returns_search_results_with_rate_limit_headers() throws Exception {
		when(rateLimitService.acquire(eq("tenantId")))
				.thenReturn(new RateLimitService.Result(true, 99L, 60L));
		when(rateLimitService.capacity()).thenReturn(100L);
		when(searchService.search(eq("tenantId"), eq("hello"), anyInt(), anyInt()))
				.thenReturn(new SearchResponse("hello", 1L, 0, 10, 12L,
						List.of(new SearchHit("doc-1", "Hello world", "ezio", "greeting", 1.5,
								List.of("<em>hello</em> world")))));

		mvc.perform(get("/search").param("q", "hello").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-RateLimit-Limit", "100"))
				.andExpect(header().string("X-RateLimit-Remaining", "99"))
				.andExpect(jsonPath("$.totalHits").value(1))
				.andExpect(jsonPath("$.hits[0].id").value("doc-1"))
				.andExpect(jsonPath("$.hits[0].highlights[0]").value("<em>hello</em> world"));
	}

	@Test
	void rejects_when_rate_limited() throws Exception {
		when(rateLimitService.acquire(eq("tenantId")))
				.thenReturn(new RateLimitService.Result(false, 0L, 30L));
		when(rateLimitService.capacity()).thenReturn(100L);

		mvc.perform(get("/search").param("q", "hello").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "30"));
	}

	static class TestProps {
		@org.springframework.context.annotation.Bean
		TenancyProperties tenancyProperties() {
			return new TenancyProperties("X-Tenant-ID", true,
					java.util.List.of("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**"));
		}

		@org.springframework.context.annotation.Bean
		RateLimitProperties rateLimitProperties() {
			return new RateLimitProperties(true, 100L, 60L);
		}
	}
}
