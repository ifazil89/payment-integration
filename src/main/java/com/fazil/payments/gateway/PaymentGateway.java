package com.fazil.payments.gateway;

import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.GatewayLinks;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.gateway.exception.GatewayException;
import com.fazil.payments.gateway.exception.InvalidWebhookException;
import com.fazil.payments.gateway.exception.WebhookAuthenticationException;

import org.springframework.http.HttpHeaders;

/**
 * Card payment gateway port. Implementations translate every failure into a {@link GatewayException} subtype
 * so callers can tell a definitive rejection from an unknown outcome.
 */
public interface PaymentGateway {

	/** Creates the order and submits the card. Never retried: a lost response leaves the outcome unknown. */
	GatewayPaymentResult createPayment(GatewayPaymentRequest request);

	/** Sends shopper device info; the result carries a challenge when the issuer requires one. */
	GatewayPaymentResult authenticate3ds(GatewayLinks links, BrowserInfo browserInfo, String notificationUrl);

	/** Completes 3DS with the {@code cres} value posted by the ACS, passed through unchanged. */
	GatewayPaymentResult completeChallenge(GatewayLinks links, String cres);

	/** Authoritative current state of the order. Safe to retry. */
	GatewayPaymentResult getOrder(String orderReference);

	GatewayPaymentResult refund(GatewayLinks links, Money amount);

	/**
	 * Authenticates and parses an incoming webhook.
	 * @throws WebhookAuthenticationException if the shared secret is missing or wrong
	 * @throws InvalidWebhookException if the body cannot be parsed
	 */
	GatewayWebhookEvent parseWebhook(HttpHeaders headers, String body);

}
