package com.docsearch.config;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchConfig {

	@Bean(destroyMethod = "close")
	public OpenSearchTransport openSearchTransport(OpenSearchProperties props) {
		HttpHost host = new HttpHost(props.scheme(), props.host(), props.port());

		ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);

		if (props.username() != null && !props.username().isBlank()) {
			BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
			credsProvider.setCredentials(
					new AuthScope(host),
					new UsernamePasswordCredentials(props.username(), props.password().toCharArray())
			);
			builder.setHttpClientConfigCallback(httpClientBuilder ->
					httpClientBuilder.setDefaultCredentialsProvider(credsProvider));
		}

		return builder.build();
	}

	@Bean
	public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
		return new OpenSearchClient(transport);
	}
}
