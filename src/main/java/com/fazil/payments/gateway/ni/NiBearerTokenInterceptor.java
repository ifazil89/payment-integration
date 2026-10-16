package com.fazil.payments.gateway.ni;

import java.io.IOException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Adds the cached bearer token to every N-Genius API call. A 401 means the request was not processed, so the
 * token is refreshed and the request sent exactly once more; a second 401 is returned to the caller.
 */
@Slf4j
@RequiredArgsConstructor
class NiBearerTokenInterceptor implements ClientHttpRequestInterceptor {

	private final NiAccessTokenProvider tokenProvider;

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		String token = this.tokenProvider.getToken();
		request.getHeaders().setBearerAuth(token);
		ClientHttpResponse response = execution.execute(request, body);
		if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
			return response;
		}
		response.close();
		log.info("N-Genius rejected the access token (HTTP 401), refreshing it and retrying once");
		this.tokenProvider.invalidate(token);
		request.getHeaders().setBearerAuth(this.tokenProvider.getToken());
		return execution.execute(request, body);
	}

}
