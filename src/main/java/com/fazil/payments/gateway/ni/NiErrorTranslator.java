package com.fazil.payments.gateway.ni;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.GatewayRejectedException;
import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs an N-Genius call and translates HTTP client failures into gateway exceptions, so callers can tell "not
 * processed" from "outcome unknown". Response bodies are never copied into exceptions or logs; only a sanitised
 * N-Genius error code is kept.
 */
@Slf4j
final class NiErrorTranslator {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

	private NiErrorTranslator() {
	}

	static <T> T execute(String operation, Supplier<T> call) {
		long started = System.nanoTime();
		try {
			T result = call.get();
			if (result == null) {
				throw new GatewayOutcomeUnknownException(operation, "empty response body", null);
			}
			log.debug("N-Genius {} succeeded in {} ms", operation, elapsedMillis(started));
			return result;
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			log.debug("N-Genius {} returned HTTP {} in {} ms", operation, status, elapsedMillis(started));
			if (status == HttpStatus.UNAUTHORIZED.value()) {
				// The bearer interceptor already refreshed the token once; the credentials themselves are wrong.
				throw new GatewayUnavailableException(operation, "credentials rejected (HTTP 401)", null);
			}
			if (ex.getStatusCode().is4xxClientError()) {
				throw new GatewayRejectedException(operation, status, errorCode(ex));
			}
			throw new GatewayOutcomeUnknownException(operation, "HTTP " + status, null);
		}
		catch (ResourceAccessException ex) {
			String reason = rootCause(ex).getClass().getSimpleName();
			if (neverSent(ex)) {
				throw new GatewayUnavailableException(operation, "connection failed (" + reason + ")", ex);
			}
			throw new GatewayOutcomeUnknownException(operation, "I/O failure (" + reason + ")", ex);
		}
		catch (RestClientException ex) {
			throw new GatewayOutcomeUnknownException(operation,
					"unreadable response (" + ex.getClass().getSimpleName() + ")", null);
		}
	}

	private static String errorCode(RestClientResponseException ex) {
		try {
			JsonNode errors = JSON.readTree(ex.getResponseBodyAsByteArray()).path("errors");
			String code = errors.path(0).path("errorCode").asString(null);
			return (code != null && SAFE_CODE.matcher(code).matches()) ? code : null;
		}
		catch (JacksonException | IllegalArgumentException _) {
			return null;
		}
	}

	private static boolean neverSent(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof ConnectException || t instanceof UnknownHostException
					|| t instanceof NoRouteToHostException || t instanceof HttpConnectTimeoutException) {
				return true;
			}
		}
		return false;
	}

	private static Throwable rootCause(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root;
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
