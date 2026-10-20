package com.fazil.payments.config;

import java.time.Clock;

import com.fazil.payments.common.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Async} and {@code @Scheduled} run on Boot's auto-configured executor and scheduler (virtual threads,
 * tuned via {@code spring.task.*}); Boot applies the {@link TaskDecorator} bean to the executor.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@Slf4j
class AppConfig implements AsyncConfigurer {

	/** A single injectable time source, so time-dependent logic can be tested with a fixed clock. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	TaskDecorator mdcTaskDecorator() {
		return new MdcTaskDecorator();
	}

	@Override
	public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
		return (ex, method, _) -> log.error("Async task {} failed", method.getName(), ex);
	}

}
