package com.fazil.payments.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Internal payment lifecycle. Gateway states are mapped onto this enum; anything that does not map is
 * logged and ignored rather than forced into a state.
 */
public enum PaymentStatus {

	CREATED, AWAIT_3DS, UNKNOWN, AUTHORISED, PURCHASED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, REVERSED, DECLINED,
	FAILED;

	private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

	static {
		Set<PaymentStatus> outcomes = EnumSet.of(AUTHORISED, PURCHASED, CAPTURED, DECLINED, FAILED);
		ALLOWED.put(CREATED, union(outcomes, AWAIT_3DS, UNKNOWN));
		ALLOWED.put(AWAIT_3DS, union(outcomes, UNKNOWN));
		ALLOWED.put(UNKNOWN, union(outcomes, AWAIT_3DS));
		ALLOWED.put(AUTHORISED, EnumSet.of(CAPTURED, REVERSED));
		ALLOWED.put(PURCHASED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, REVERSED));
		ALLOWED.put(CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
		ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(REFUNDED));
		for (PaymentStatus status : values()) {
			ALLOWED.putIfAbsent(status, EnumSet.noneOf(PaymentStatus.class));
		}
	}

	public boolean canTransitionTo(PaymentStatus target) {
		return ALLOWED.get(this).contains(target);
	}

	/** Outcome not yet known: the payment needs follow-up with the gateway. */
	public boolean isPending() {
		return this == CREATED || this == AWAIT_3DS || this == UNKNOWN;
	}

	public boolean isRefundable() {
		return this == PURCHASED || this == CAPTURED || this == PARTIALLY_REFUNDED;
	}

	private static Set<PaymentStatus> union(Set<PaymentStatus> base, PaymentStatus... extra) {
		EnumSet<PaymentStatus> result = EnumSet.copyOf(base);
		result.addAll(Set.of(extra));
		return result;
	}

}
