package com.docsearch.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(DocumentNotFoundException.class)
	public ResponseEntity<ApiError> notFound(DocumentNotFoundException ex, HttpServletRequest req) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiError.of(404, "Not Found", ex.getMessage(), req.getRequestURI()));
	}

	@ExceptionHandler(TenantRequiredException.class)
	public ResponseEntity<ApiError> tenantRequired(TenantRequiredException ex, HttpServletRequest req) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of(400, "Bad Request", ex.getMessage(), req.getRequestURI()));
	}

	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiError> rateLimited(RateLimitExceededException ex, HttpServletRequest req) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.headers(headers)
				.body(ApiError.of(429, "Too Many Requests", ex.getMessage(), req.getRequestURI()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
		List<String> details = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
				.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of(400, "Validation Failed", "Invalid request", req.getRequestURI(), details));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> illegalArg(IllegalArgumentException ex, HttpServletRequest req) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of(400, "Bad Request", ex.getMessage(), req.getRequestURI()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> generic(Exception ex, HttpServletRequest req) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
	}
}
