package com.fazil.payments.gateway.ni;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import com.fazil.payments.gateway.ni.dto.NiAccessTokenResponse;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Caches the N-Genius access token (300 s lifetime) and refreshes it {@code tokenRefreshMargin} before
 * expiry. Only one thread fetches at a time; the token itself is never logged.
 */
@Slf4j
class NiAccessTokenProvider {

	private static final String OPERATION = "access-token";

	private static final String TOKEN_URI = "/identity/auth/access-token";

	private final RestClient identityClient;

	private final NiProperties properties;

	private final NiCallExecutor calls;

	private final Clock clock;

	private final ReentrantLock lock = new ReentrantLock();

	private volatile CachedToken cached;

	NiAccessTokenProvider(RestClient identityClient, NiProperties properties, NiCallExecutor calls, Clock clock) {
		this.identityClient = identityClient;
		this.properties = properties;
		this.calls = calls;
		this.clock = clock;
	}

	String getToken() {
		CachedToken current = this.cached;
		if (current != null && current.isUsableAt(this.clock.instant())) {
			return current.value();
		}
		this.lock.lock();
		try {
			current = this.cached;
			if (current == null || !current.isUsableAt(this.clock.instant())) {
				current = fetch();
				this.cached = current;
			}
			return current.value();
		}
		finally {
			this.lock.unlock();
		}
	}

	/** Drops the cached token if it is still the one that was rejected. */
	void invalidate(String rejectedToken) {
		this.lock.lock();
		try {
			if (this.cached != null && this.cached.value().equals(rejectedToken)) {
				this.cached = null;
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	private CachedToken fetch() {
		NiAccessTokenResponse response = this.calls.callIdempotent(OPERATION, () -> this.identityClient.post()
			.uri(TOKEN_URI)
			.header(HttpHeaders.AUTHORIZATION, "Basic " + this.properties.apiKey())
			.contentType(NiMediaTypes.IDENTITY_V1)
			.retrieve()
			.body(NiAccessTokenResponse.class));
		if (response.accessToken() == null || response.accessToken().isBlank()) {
			throw new GatewayUnavailableException(OPERATION, "no access_token in response", null);
		}
		Duration lifetime = Duration.ofSeconds(response.expiresIn());
		Duration margin = this.properties.tokenRefreshMargin();
		Duration usable = (lifetime.compareTo(margin) > 0) ? lifetime.minus(margin) : lifetime.dividedBy(2);
		log.info("Obtained N-Genius access token, valid for {} s", response.expiresIn());
		return new CachedToken(response.accessToken(), this.clock.instant().plus(usable));
	}

	private record CachedToken(String value, Instant refreshAt) {

		boolean isUsableAt(Instant now) {
			return now.isBefore(this.refreshAt);
		}

		@Override
		public String toString() {
			return "CachedToken[value=***, refreshAt=" + this.refreshAt + "]";
		}

	}

}
