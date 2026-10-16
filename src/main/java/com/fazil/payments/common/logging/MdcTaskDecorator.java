package com.fazil.payments.common.logging;

import java.util.Map;

import org.slf4j.MDC;

import org.springframework.core.task.TaskDecorator;

/** Copies the submitting thread's MDC (and so its correlation id) onto async tasks. */
public class MdcTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable task) {
		Map<String, String> context = MDC.getCopyOfContextMap();
		return () -> {
			if (context != null) {
				MDC.setContextMap(context);
			}
			try {
				task.run();
			}
			finally {
				MDC.clear();
			}
		};
	}

}
