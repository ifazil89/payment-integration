package com.fazil.payments.config;

import com.fazil.payments.common.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} runs on Boot's auto-configured {@code applicationTaskExecutor} (virtual threads, tuned via
 * {@code spring.task.execution.*}); Boot applies the {@link TaskDecorator} bean to it.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@Slf4j
class AsyncConfig implements AsyncConfigurer {

	@Bean
	TaskDecorator mdcTaskDecorator() {
		return new MdcTaskDecorator();
	}

	@Override
	public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
		return (ex, method, params) -> log.error("Async task {} failed", method.getName(), ex);
	}

}
