package com.fazil.payments.gateway.ni;

import java.time.Clock;

import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the N-Genius adapter. Starts from Boot's auto-configured {@link RestClient.Builder} so message
 * converters and HTTP client observations (metrics, tracing) apply to every N-Genius call.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
class NiGatewayConfiguration {

	@Bean
	NiGatewayClient niGatewayClient(RestClient.Builder restClientBuilder, NiProperties properties,
			JsonMapper jsonMapper, Clock clock) {
		RestClient.Builder niBuilder = restClientBuilder.clone()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory(properties));
		NiCallExecutor calls = new NiCallExecutor(circuitBreaker(properties.circuitBreaker()),
				retryTemplate(properties.retry()));
		NiAccessTokenProvider tokenProvider = new NiAccessTokenProvider(niBuilder.clone().build(), properties, calls,
				clock);
		RestClient apiClient = niBuilder.clone()
			.requestInterceptor(new NiBearerTokenInterceptor(tokenProvider))
			.build();
		return new NiGatewayClient(apiClient, properties, new NiPaymentMapper(jsonMapper), calls, jsonMapper);
	}

	private static ClientHttpRequestFactory requestFactory(NiProperties properties) {
		// _links are followed explicitly; a redirect could send the bearer token somewhere unexpected.
		return ClientHttpRequestFactoryBuilder.jdk()
			.build(HttpClientSettings.defaults()
				.withTimeouts(properties.connectTimeout(), properties.readTimeout())
				.withRedirects(HttpRedirects.DONT_FOLLOW));
	}

	/** Only for idempotent calls; transient failures are the two outcomes worth trying again. */
	private static RetryTemplate retryTemplate(NiProperties.Retry retry) {
		return new RetryTemplate(RetryPolicy.builder()
			.maxRetries(retry.maxRetries())
			.delay(retry.initialDelay())
			.multiplier(retry.multiplier())
			.includes(GatewayUnavailableException.class, GatewayOutcomeUnknownException.class)
			.build());
	}

	/** Gateway-side failures trip the breaker; 4xx rejections are business answers and count as successes. */
	private static CircuitBreaker circuitBreaker(NiProperties.Breaker breaker) {
		CircuitBreaker circuitBreaker = CircuitBreaker.of("ni", CircuitBreakerConfig.custom()
			.slidingWindowSize(breaker.slidingWindowSize())
			.minimumNumberOfCalls(breaker.minimumCalls())
			.failureRateThreshold(breaker.failureRateThreshold())
			.waitDurationInOpenState(breaker.waitInOpenState())
			.recordExceptions(GatewayUnavailableException.class, GatewayOutcomeUnknownException.class)
			.build());
		circuitBreaker.getEventPublisher()
			.onStateTransition(event -> log.warn("N-Genius circuit breaker {}", event.getStateTransition()));
		return circuitBreaker;
	}

}
