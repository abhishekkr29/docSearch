package com.docsearch.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docsearch.config.RateLimitProperties;
import com.docsearch.config.TenancyProperties;
import com.docsearch.document.dto.DocumentResponse;
import com.docsearch.exception.DocumentNotFoundException;
import com.docsearch.exception.GlobalExceptionHandler;
import com.docsearch.ratelimit.RateLimitFilter;
import com.docsearch.ratelimit.RateLimitService;
import com.docsearch.tenancy.TenantFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DocumentController.class)
@Import({GlobalExceptionHandler.class, TenantFilter.class, RateLimitFilter.class,
		DocumentControllerWebTest.TestProps.class})
class DocumentControllerWebTest {

	@Autowired MockMvc mvc;
	@MockitoBean DocumentService documentService;
	@MockitoBean RateLimitService rateLimitService;

	@BeforeEach
	void allowAllByDefault() {
		when(rateLimitService.acquire(eq("tenantId")))
				.thenReturn(new RateLimitService.Result(true, 99L, 60L));
		when(rateLimitService.capacity()).thenReturn(100L);
	}

	@Test
	void index_returns_201_with_persisted_document() throws Exception {
		DocumentResponse persisted = new DocumentResponse(
				"id-1", "tenantId", "Hello", "world body", "ezio", "greeting",
				Instant.parse("2026-04-28T10:00:00Z"),
				Instant.parse("2026-04-28T10:00:00Z"));
		when(documentService.create(eq("tenantId"), any())).thenReturn(persisted);

		mvc.perform(post("/documents")
						.header("X-Tenant-ID", "tenantId")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Hello","content":"world body","author":"ezio","tags":"greeting"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value("id-1"))
				.andExpect(jsonPath("$.tenantId").value("tenantId"))
				.andExpect(header().string("X-RateLimit-Remaining", "99"));
	}

	@Test
	void index_with_blank_title_returns_400_validation_envelope() throws Exception {
		mvc.perform(post("/documents")
						.header("X-Tenant-ID", "tenantId")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"","content":"body"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.details").isArray())
				.andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
						org.hamcrest.Matchers.containsString("title"))));
	}

	@Test
	void index_with_missing_tenant_returns_400() throws Exception {
		mvc.perform(post("/documents")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Hello","content":"body"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message",
						org.hamcrest.Matchers.containsString("X-Tenant-ID")));
	}

	@Test
	void index_with_invalid_tenant_format_returns_400() throws Exception {
		mvc.perform(post("/documents")
						.header("X-Tenant-ID", "bad/tenant!")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Hello","content":"body"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void get_returns_200_with_document() throws Exception {
		DocumentResponse persisted = new DocumentResponse(
				"id-1", "tenantId", "Hello", "body", "ezio", "tag",
				Instant.parse("2026-04-28T10:00:00Z"),
				Instant.parse("2026-04-28T10:00:00Z"));
		when(documentService.get("tenantId", "id-1")).thenReturn(persisted);

		mvc.perform(get("/documents/id-1").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("id-1"))
				.andExpect(jsonPath("$.title").value("Hello"));
	}

	@Test
	void get_unknown_id_returns_404_envelope() throws Exception {
		when(documentService.get("tenantId", "missing"))
				.thenThrow(new DocumentNotFoundException("missing"));

		mvc.perform(get("/documents/missing").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.path").value("/documents/missing"));
	}

	@Test
	void delete_returns_204() throws Exception {
		mvc.perform(delete("/documents/id-1").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isNoContent());
		verify(documentService, times(1)).delete("tenantId", "id-1");
	}

	@Test
	void delete_unknown_id_returns_404() throws Exception {
		doThrow(new DocumentNotFoundException("missing"))
				.when(documentService).delete("tenantId", "missing");

		mvc.perform(delete("/documents/missing").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isNotFound());
	}

	@Test
	void rate_limited_request_returns_429_with_retry_after() throws Exception {
		when(rateLimitService.acquire("tenantId"))
				.thenReturn(new RateLimitService.Result(false, 0L, 30L));

		mvc.perform(get("/documents/id-1").header("X-Tenant-ID", "tenantId"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(jsonPath("$.status").value(429));
	}

	static class TestProps {
		@Bean
		TenancyProperties tenancyProperties() {
			return new TenancyProperties("X-Tenant-ID", true,
					List.of("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**"));
		}

		@Bean
		RateLimitProperties rateLimitProperties() {
			return new RateLimitProperties(true, 100L, 60L);
		}
	}
}
