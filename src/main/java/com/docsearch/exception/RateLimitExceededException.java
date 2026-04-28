package com.docsearch.exception;

public class RateLimitExceededException extends RuntimeException {
	private final long retryAfterSeconds;

	public RateLimitExceededException(String tenantId, long retryAfterSeconds) {
		super("Rate limit exceeded for tenant " + tenantId);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
