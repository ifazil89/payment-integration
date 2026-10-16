package com.fazil.payments.gateway.ni;

import java.util.Locale;
import java.util.Optional;

import com.fazil.payments.domain.model.PaymentStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Normalises N-Genius payment states. The docs spell some states more than one way, and new states may
 * appear, so anything unrecognised is logged and left unmapped instead of failing.
 */
@Slf4j
final class NiStateMapper {

	private NiStateMapper() {
	}

	static Optional<PaymentStatus> toStatus(String niState) {
		if (niState == null || niState.isBlank()) {
			return Optional.empty();
		}
		PaymentStatus status = switch (niState.trim().toUpperCase(Locale.ROOT)) {
			case "STARTED" -> PaymentStatus.CREATED;
			case "AWAIT_3DS", "AWAITING_3DS" -> PaymentStatus.AWAIT_3DS;
			case "AUTHORISED", "AUTHORIZED" -> PaymentStatus.AUTHORISED;
			case "PURCHASED", "PURCHASE" -> PaymentStatus.PURCHASED;
			case "CAPTURED" -> PaymentStatus.CAPTURED;
			case "PARTIALLY_REFUNDED" -> PaymentStatus.PARTIALLY_REFUNDED;
			case "REFUNDED" -> PaymentStatus.REFUNDED;
			case "REVERSED", "PURCHASE_REVERSED", "FULL_AUTH_REVERSED" -> PaymentStatus.REVERSED;
			case "DECLINED", "PURCHASE_DECLINED" -> PaymentStatus.DECLINED;
			case "FAILED", "PURCHASE_FAILED", "AUTHORISATION_FAILED" -> PaymentStatus.FAILED;
			default -> null;
		};
		if (status == null) {
			log.warn("Unrecognised N-Genius payment state '{}', leaving status unchanged", niState);
		}
		return Optional.ofNullable(status);
	}

}
