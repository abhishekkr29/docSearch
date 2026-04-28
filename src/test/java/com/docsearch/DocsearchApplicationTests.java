package com.docsearch;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Trivial smoke test. The full Spring application context is not booted
 * here because it requires OpenSearch + Redis + a database to be running.
 * The Web slice in {@code SearchControllerWebTest} covers controller wiring,
 * {@code RateLimitServiceTest} covers the limiter logic, and
 * {@code docker compose up} is the integration story for the prototype.
 */
class DocsearchApplicationTests {

	@Test
	void mainClassLoads() {
		assertNotNull(DocsearchApplication.class.getName());
	}
}
