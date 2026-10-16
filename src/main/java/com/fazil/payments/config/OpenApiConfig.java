package com.fazil.payments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	@Bean
	OpenAPI paymentsOpenApi(@Value("${spring.application.name}") String applicationName) {
		return new OpenAPI().info(new Info().title(applicationName)
			.version("v1")
			.description("Card payments through Network International N-Genius (one-stage Direct API with EMV 3DS)."));
	}

}
