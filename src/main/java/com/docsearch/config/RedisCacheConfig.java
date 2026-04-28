package com.docsearch.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisCacheConfig {

	public static final String SEARCH_CACHE = "search";
	public static final String DOCUMENT_CACHE = "document";

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory cf, CacheProperties props) {
		ObjectMapper mapper = new ObjectMapper()
				.registerModule(new JavaTimeModule())
				.activateDefaultTyping(
						BasicPolymorphicTypeValidator.builder()
								.allowIfSubType("com.docsearch.")
								.allowIfSubType("java.util.")
								.allowIfSubType("java.time.")
								.allowIfSubType("java.lang.")
								.build(),
						ObjectMapper.DefaultTyping.NON_FINAL);
		mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

		GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
				.disableCachingNullValues()
				.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
				.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

		Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
		perCache.put(SEARCH_CACHE, base.entryTtl(Duration.ofSeconds(props.searchTtlSeconds())));
		perCache.put(DOCUMENT_CACHE, base.entryTtl(Duration.ofSeconds(props.documentTtlSeconds())));

		return RedisCacheManager.builder(cf)
				.cacheDefaults(base.entryTtl(Duration.ofSeconds(60)))
				.withInitialCacheConfigurations(perCache)
				.build();
	}
}
