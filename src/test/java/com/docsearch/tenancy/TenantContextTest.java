package com.docsearch.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	@Test
	void set_and_get_round_trip() {
		TenantContext.set("acme");
		assertThat(TenantContext.get()).isEqualTo("acme");
		assertThat(TenantContext.require()).isEqualTo("acme");
	}

	@Test
	void clear_resets_to_null() {
		TenantContext.set("acme");
		TenantContext.clear();
		assertThat(TenantContext.get()).isNull();
	}

	@Test
	void require_throws_when_unset() {
		assertThatThrownBy(TenantContext::require)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Tenant context");
	}

	@Test
	void require_throws_when_blank() {
		TenantContext.set("   ");
		assertThatThrownBy(TenantContext::require)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void thread_local_isolates_concurrent_threads() throws InterruptedException {
		TenantContext.set("main-tenant");

		AtomicReference<String> seenInOtherThread = new AtomicReference<>("UNSET");
		CountDownLatch done = new CountDownLatch(1);

		Thread t = new Thread(() -> {
			seenInOtherThread.set(TenantContext.get());
			TenantContext.set("other-tenant");
			done.countDown();
		});
		t.start();
		done.await();
		t.join();

		assertThat(seenInOtherThread.get()).isNull();
		assertThat(TenantContext.get()).isEqualTo("main-tenant");
	}
}
