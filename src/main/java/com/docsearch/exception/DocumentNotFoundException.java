package com.docsearch.exception;

public class DocumentNotFoundException extends RuntimeException {
	public DocumentNotFoundException(String id) {
		super("Document not found: " + id);
	}
}
