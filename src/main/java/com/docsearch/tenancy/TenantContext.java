package com.docsearch.tenancy;

public final class TenantContext {

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private TenantContext() {}

	public static void set(String tenantId) {
		CURRENT.set(tenantId);
	}

	public static String get() {
		return CURRENT.get();
	}

	public static String require() {
		String t = CURRENT.get();
		if (t == null || t.isBlank()) {
			throw new IllegalStateException("Tenant context is not set");
		}
		return t;
	}

	public static void clear() {
		CURRENT.remove();
	}
}
