package com.docsearch.exception;

public class TenantRequiredException extends RuntimeException {
	public TenantRequiredException(String header) {
		super("Missing required tenant header: " + header);
	}
}
