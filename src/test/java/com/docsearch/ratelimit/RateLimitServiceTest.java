package com.docsearch.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docsearch.config.RateLimitProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings({"unchecked"})
class RateLimitServiceTest {

	private final RedisTemplate<String, String> redis = Mockito.mock(RedisTemplate.class);

	@Test
	void allows_when_within_capacity_and_reports_remaining() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenReturn(List.of(42L, 60L));

		RateLimitService svc = new RateLimitService(redis,
				new RateLimitProperties(true, 100L, 60L));

		RateLimitService.Result result = svc.acquire("acme");

		assertThat(result.allowed()).isTrue();
		assertThat(result.remaining()).isEqualTo(42L);
		assertThat(result.retryAfterSeconds()).isEqualTo(60L);
	}

	@Test
	void rejects_when_capacity_exceeded() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenReturn(List.of(-1L, 17L));

		RateLimitService svc = new RateLimitService(redis,
				new RateLimitProperties(true, 100L, 60L));

		RateLimitService.Result result = svc.acquire("acme");

		assertThat(result.allowed()).isFalse();
		assertThat(result.remaining()).isZero();
		assertThat(result.retryAfterSeconds()).isEqualTo(17L);
	}

	@Test
	void retry_after_is_at_least_one_second_to_avoid_zero_retry_after_header() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenReturn(List.of(-1L, 0L));

		RateLimitService svc = new RateLimitService(redis,
				new RateLimitProperties(true, 100L, 60L));

		RateLimitService.Result result = svc.acquire("acme");

		assertThat(result.allowed()).isFalse();
		assertThat(result.retryAfterSeconds()).isEqualTo(1L);
	}

	@Test
	void disabled_limiter_short_circuits_without_touching_redis() {
		RateLimitService svc = new RateLimitService(redis,
				new RateLimitProperties(false, 100L, 60L));

		RateLimitService.Result result = svc.acquire("acme");

		assertThat(result.allowed()).isTrue();
		assertThat(result.remaining()).isEqualTo(100L);
		verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any());
	}

	@Test
	void key_is_scoped_per_tenant_and_per_window() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenReturn(List.of(99L, 60L));

		RateLimitService svc = new RateLimitService(redis,
				new RateLimitProperties(true, 100L, 60L));

		svc.acquire("acme");
		svc.acquire("globex");

		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redis, Mockito.times(2)).execute(any(RedisScript.class),
				keysCaptor.capture(), eq("100"), eq("60"));

		List<List<String>> calls = keysCaptor.getAllValues();
		assertThat(calls.get(0).get(0)).startsWith("rl:acme:");
		assertThat(calls.get(1).get(0)).startsWith("rl:globex:");
		assertThat(calls.get(0).get(0)).isNotEqualTo(calls.get(1).get(0));
	}
}
