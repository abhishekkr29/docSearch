package com.docsearch.ratelimit;

import com.docsearch.config.RateLimitProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Distributed fixed-window rate limiter using a Lua script for atomic
 * INCR + EXPIRE. Returns the number of remaining tokens, or -1 if the
 * tenant has exceeded the configured capacity.
 *
 * Fixed-window is simple and good enough for a prototype. Production should
 * switch to a sliding-window or token-bucket (e.g. Bucket4j) for smoother
 * traffic shaping at burst boundaries.
 */
@Service
public class RateLimitService {

	private static final String LUA = """
			local current = redis.call('INCR', KEYS[1])
			if current == 1 then
			  redis.call('EXPIRE', KEYS[1], ARGV[2])
			end
			local ttl = redis.call('TTL', KEYS[1])
			if current > tonumber(ARGV[1]) then
			  return {-1, ttl}
			end
			return {tonumber(ARGV[1]) - current, ttl}
			""";

	private final RedisTemplate<String, String> redis;
	private final RateLimitProperties props;
	private final RedisScript<List> script;
	
	public RateLimitService(RedisTemplate<String, String> redis, RateLimitProperties props) {
		this.redis = redis;
		this.props = props;
		this.script = new DefaultRedisScript<>(LUA, List.class);
	}

	public Result acquire(String tenantId) {
		if (!props.enabled()) {
			return new Result(true, props.capacity(), 0);
		}
		long windowEpoch = System.currentTimeMillis() / 1000 / props.windowSeconds();
		String key = "rl:" + tenantId + ":" + windowEpoch;

		@SuppressWarnings("unchecked")
		List<Long> result = redis.execute(script, List.of(key),
				String.valueOf(props.capacity()),
				String.valueOf(props.windowSeconds()));

		long remaining = result.get(0);
		long ttl = result.get(1);
		if (remaining < 0) {
			return new Result(false, 0, Math.max(ttl, 1));
		}
		return new Result(true, remaining, Math.max(ttl, 1));
	}

	public long capacity() {
		return props.capacity();
	}

	public Duration window() {
		return Duration.ofSeconds(props.windowSeconds());
	}

	public record Result(boolean allowed, long remaining, long retryAfterSeconds) {}
}
