package com.fazil.payments.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run on Boot's auto-configured scheduler, tuned via {@code spring.task.scheduling.*}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

}
