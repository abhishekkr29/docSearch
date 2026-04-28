package com.docsearch.document;

import com.docsearch.document.dto.DocumentRequest;
import com.docsearch.document.dto.DocumentResponse;
import com.docsearch.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/documents")
@Tag(name = "Documents", description = "Document CRUD operations")
public class DocumentController {

	private final DocumentService service;

	public DocumentController(DocumentService service) {
		this.service = service;
	}

	@PostMapping
	@Operation(summary = "Index a new document")
	public ResponseEntity<DocumentResponse> create(@Valid @RequestBody DocumentRequest req) {
		DocumentResponse created = service.create(TenantContext.require(), req);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Retrieve a document by id")
	public DocumentResponse get(@PathVariable String id) {
		return service.get(TenantContext.require(), id);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a document by id")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		service.delete(TenantContext.require(), id);
		return ResponseEntity.noContent().build();
	}
}
