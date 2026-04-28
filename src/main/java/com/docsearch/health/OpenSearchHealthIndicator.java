package com.docsearch.health;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("opensearch")
public class OpenSearchHealthIndicator implements HealthIndicator {

	private final OpenSearchClient client;

	public OpenSearchHealthIndicator(OpenSearchClient client) {
		this.client = client;
	}

	@Override
	public Health health() {
		try {
			var info = client.info();
			return Health.up()
					.withDetail("clusterName", info.clusterName())
					.withDetail("version", info.version().number())
					.build();
		} catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
