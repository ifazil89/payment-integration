package com.fazil.payments.gateway.ni;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.GatewayLinks;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.domain.model.ThreeDsChallenge;
import com.fazil.payments.gateway.GatewayPaymentRequest;
import com.fazil.payments.gateway.GatewayWebhookEvent;
import com.fazil.payments.gateway.PaymentGateway;
import com.fazil.payments.gateway.exception.InvalidWebhookException;
import com.fazil.payments.gateway.exception.WebhookAuthenticationException;
import com.fazil.payments.gateway.ni.dto.NiChallengeResponseRequest;
import com.fazil.payments.gateway.ni.dto.NiOrderResponse;
import com.fazil.payments.gateway.ni.dto.NiPaymentResponse;
import com.fazil.payments.gateway.ni.dto.NiRefundRequest;
import com.fazil.payments.gateway.ni.dto.NiWebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * N-Genius one-stage Direct API adapter. Every follow-up call uses the {@code _links} hrefs N-Genius returned;
 * only the entry points (payment, order lookup) are built from configuration. Authentication is added by
 * {@link NiBearerTokenInterceptor}; circuit breaking, retries and error translation by {@link NiCallExecutor}.
 */
@Slf4j
@RequiredArgsConstructor
class NiGatewayClient implements PaymentGateway {

	private static final String CREATE_PAYMENT_URI = "/transactions/outlets/{outletRef}/payment/card";

	private static final String ORDER_URI = "/transactions/outlets/{outletRef}/orders/{orderRef}";

	private final RestClient restClient;

	private final NiProperties properties;

	private final NiPaymentMapper mapper;

	private final NiCallExecutor calls;

	private final JsonMapper jsonMapper;

	@Override
	public GatewayPaymentResult createPayment(GatewayPaymentRequest request) {
		NiPaymentResponse response = this.calls.call("create-payment", () -> this.restClient.post()
			.uri(CREATE_PAYMENT_URI, this.properties.outletRef())
			.contentType(NiMediaTypes.PAYMENT_V2)
			.accept(NiMediaTypes.PAYMENT_V2)
			.body(this.mapper.toCardPaymentRequest(request, this.properties.orderAction()))
			.retrieve()
			.body(NiPaymentResponse.class));
		return this.mapper.toResult(response);
	}

	@Override
	public GatewayPaymentResult authenticate3ds(GatewayLinks links, BrowserInfo browser, String notificationUrl) {
		URI href = requireLink(links.threeDsAuthentication(), "3ds-authentication");
		NiPaymentResponse response = this.calls.call("3ds-authentication", () -> this.restClient.post()
			.uri(href)
			.contentType(NiMediaTypes.PAYMENT_V2)
			.body(this.mapper.toAuthenticationRequest(browser, notificationUrl))
			.retrieve()
			.body(NiPaymentResponse.class));
		PaymentStatus status = NiStateMapper.toStatus(response.state()).orElse(null);
		ThreeDsChallenge challenge = (status == PaymentStatus.AWAIT_3DS && response.threeDs2() != null
				&& response.threeDs2().isChallengeRequired())
						? this.mapper.toChallenge(response.threeDs2(), browser.challengeWindowSize()) : null;
		return this.mapper.toResult(response, challenge, null);
	}

	@Override
	public GatewayPaymentResult completeChallenge(GatewayLinks links, String cres) {
		URI href = requireLink(links.threeDsChallengeResponse(), "3ds-challenge-response");
		NiPaymentResponse response = this.calls.call("3ds-challenge-response", () -> this.restClient.post()
			.uri(href)
			.contentType(NiMediaTypes.PAYMENT_V2)
			.body(new NiChallengeResponseRequest(cres))
			.retrieve()
			.body(NiPaymentResponse.class));
		return this.mapper.toResult(response);
	}

	@Override
	public GatewayPaymentResult getOrder(String orderReference) {
		NiOrderResponse order = this.calls.callIdempotent("get-order", () -> this.restClient.get()
			.uri(ORDER_URI, this.properties.outletRef(), orderReference)
			.retrieve()
			.body(NiOrderResponse.class));
		NiPaymentResponse payment = order.firstPayment();
		if (payment == null) {
			log.warn("N-Genius order {} has no embedded payment", orderReference);
			return GatewayPaymentResult.empty(orderReference);
		}
		return this.mapper.toResult(payment, null, (order.reference() != null) ? order.reference() : orderReference);
	}

	@Override
	public GatewayPaymentResult refund(GatewayLinks links, Money amount) {
		URI href = requireLink(links.refund(), "refund");
		NiPaymentResponse response = this.calls.call("refund", () -> this.restClient.post()
			.uri(href)
			.contentType(NiMediaTypes.PAYMENT_V2)
			.accept(NiMediaTypes.PAYMENT_V2)
			.body(new NiRefundRequest(this.mapper.toAmount(amount)))
			.retrieve()
			.body(NiPaymentResponse.class));
		return this.mapper.toResult(response);
	}

	@Override
	public GatewayWebhookEvent parseWebhook(HttpHeaders headers, String body) {
		verifyWebhookSecret(headers.getFirst(this.properties.webhook().headerName()));
		NiWebhookEvent event;
		try {
			event = this.jsonMapper.readValue(body, NiWebhookEvent.class);
		}
		catch (JacksonException ex) {
			throw new InvalidWebhookException("Malformed N-Genius webhook payload");
		}
		if (event == null || event.eventId() == null || event.eventId().isBlank()) {
			throw new InvalidWebhookException("N-Genius webhook payload has no eventId");
		}
		return this.mapper.toWebhookEvent(event);
	}

	/** Constant-time comparison so the secret cannot be guessed from response timing. */
	private void verifyWebhookSecret(String provided) {
		byte[] expected = this.properties.webhook().headerValue().getBytes(StandardCharsets.UTF_8);
		if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
			throw new WebhookAuthenticationException("Missing or invalid N-Genius webhook secret header");
		}
	}

	private static URI requireLink(String href, String operation) {
		if (href == null || href.isBlank()) {
			throw new IllegalStateException("N-Genius did not provide a link for " + operation);
		}
		return URI.create(href);
	}

}
