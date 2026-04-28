package com.docsearch.exception;

import java.time.Instant;
import java.util.List;

public record ApiError(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		List<String> details
) {
	public static ApiError of(int status, String error, String message, String path) {
		return new ApiError(Instant.now(), status, error, message, path, null);
	}

	public static ApiError of(int status, String error, String message, String path, List<String> details) {
		return new ApiError(Instant.now(), status, error, message, path, details);
	}
}
