package com.fazil.payments.common.logging;

import java.util.UUID;

import org.slf4j.MDC;

/** Correlation id shared by HTTP requests, async tasks and scheduled jobs, carried in the logging MDC. */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	private CorrelationId() {
	}

	/**
	 * Runs {@code task} under a new correlation id ({@code prefix} + UUID) unless the current thread already has
	 * one, e.g. propagated by {@link MdcTaskDecorator}.
	 */
	public static void runWithCorrelationId(String prefix, Runnable task) {
		if (MDC.get(MDC_KEY) != null) {
			task.run();
			return;
		}
		MDC.put(MDC_KEY, prefix + UUID.randomUUID());
		try {
			task.run();
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

}
