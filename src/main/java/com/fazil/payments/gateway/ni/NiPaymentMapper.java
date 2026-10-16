package com.fazil.payments.gateway.ni;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.GatewayLinks;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.domain.model.ThreeDsChallenge;
import com.fazil.payments.gateway.GatewayPaymentRequest;
import com.fazil.payments.gateway.GatewayWebhookEvent;
import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.ni.dto.NiAmount;
import com.fazil.payments.gateway.ni.dto.NiBrowserInfo;
import com.fazil.payments.gateway.ni.dto.NiCardPayment;
import com.fazil.payments.gateway.ni.dto.NiCardPaymentRequest;
import com.fazil.payments.gateway.ni.dto.NiOrder;
import com.fazil.payments.gateway.ni.dto.NiPaymentMethod;
import com.fazil.payments.gateway.ni.dto.NiPaymentResponse;
import com.fazil.payments.gateway.ni.dto.NiThreeDs2;
import com.fazil.payments.gateway.ni.dto.NiThreeDsAuthenticationRequest;
import com.fazil.payments.gateway.ni.dto.NiWebhookEvent;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Converts between the gateway port's model and the N-Genius wire format. */
@RequiredArgsConstructor
class NiPaymentMapper {

	private static final String DEVICE_CHANNEL_BROWSER = "BRW";

	private static final String THREE_DS_METHOD_COMPLETED = "Y";

	private final JsonMapper jsonMapper;

	NiCardPaymentRequest toCardPaymentRequest(GatewayPaymentRequest request, String orderAction) {
		CardDetails card = request.card();
		return new NiCardPaymentRequest(
				new NiOrder(orderAction, toAmount(request.amount()), request.merchantReference()),
				new NiCardPayment(card.pan(), card.expiry(), card.cvv(), card.cardholderName()));
	}

	NiThreeDsAuthenticationRequest toAuthenticationRequest(BrowserInfo browser, String notificationUrl) {
		return new NiThreeDsAuthenticationRequest(DEVICE_CHANNEL_BROWSER, THREE_DS_METHOD_COMPLETED, notificationUrl,
				new NiBrowserInfo(browser.acceptHeader(), browser.javaEnabled(), browser.language(), browser.timeZone(),
						browser.userAgent(), browser.colorDepth(), browser.screenHeight(), browser.screenWidth(),
						browser.javascriptEnabled(), browser.ip(), browser.challengeWindowSize()));
	}

	NiAmount toAmount(Money money) {
		return new NiAmount(money.currency(), money.minorUnits());
	}

	GatewayPaymentResult toResult(NiPaymentResponse payment) {
		return toResult(payment, null, null);
	}

	/**
	 * @param challenge the challenge to hand to the shopper, if any
	 * @param orderReference overrides the payment's own order reference (used for embedded payments)
	 */
	GatewayPaymentResult toResult(NiPaymentResponse payment, ThreeDsChallenge challenge, String orderReference) {
		PaymentStatus status = NiStateMapper.toStatus(payment.state()).orElse(null);
		GatewayLinks links = new GatewayLinks(payment.link(NiPaymentResponse.REL_SELF),
				payment.link(NiPaymentResponse.REL_3DS_AUTHENTICATION),
				payment.link(NiPaymentResponse.REL_3DS_CHALLENGE_RESPONSE),
				payment.link(NiPaymentResponse.REL_REFUND));
		NiPaymentMethod method = payment.paymentMethod();
		String maskedPan = (method != null && method.pan() != null)
				? (method.pan().contains("*") ? method.pan() : CardDetails.maskPan(method.pan())) : null;
		return new GatewayPaymentResult((orderReference != null) ? orderReference : payment.orderReference(),
				payment.reference(), status, payment.state(), links, challenge, maskedPan,
				(method != null) ? method.name() : null,
				(payment.authResponse() != null) ? payment.authResponse().resultCode() : null);
	}

	/**
	 * CReq JSON per the one-stage spec, base64url-encoded without padding as EMV 3DS requires. The shopper's
	 * browser form-POSTs it to the ACS as field {@code creq}.
	 */
	ThreeDsChallenge toChallenge(NiThreeDs2 threeDs2, String challengeWindowSize) {
		if (threeDs2.acsURL() == null) {
			throw new GatewayOutcomeUnknownException("3ds-authentication", "challenge required but no acsURL", null);
		}
		Map<String, String> creq = new LinkedHashMap<>();
		creq.put("threeDSServerTransID", threeDs2.threeDSServerTransID());
		creq.put("acsTransID", threeDs2.acsTransID());
		creq.put("challengeWindowSize", challengeWindowSize);
		creq.put("messageType", "CReq");
		creq.put("messageVersion", threeDs2.messageVersion());
		String encoded = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(this.jsonMapper.writeValueAsBytes(creq));
		return new ThreeDsChallenge(threeDs2.acsURL(), encoded);
	}

	GatewayWebhookEvent toWebhookEvent(NiWebhookEvent event) {
		NiWebhookEvent.Order order = event.order();
		return new GatewayWebhookEvent(event.eventId(), event.eventName(), (order != null) ? order.reference() : null,
				(order != null) ? order.merchantOrderReference() : null);
	}

}
