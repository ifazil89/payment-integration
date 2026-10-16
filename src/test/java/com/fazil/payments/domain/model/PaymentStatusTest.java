package com.fazil.payments.domain.model;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

	@Test
	void pendingStatesCanReachEveryOutcome() {
		for (PaymentStatus pending : new PaymentStatus[] { PaymentStatus.CREATED, PaymentStatus.AWAIT_3DS,
				PaymentStatus.UNKNOWN }) {
			assertThat(pending.canTransitionTo(PaymentStatus.PURCHASED)).isTrue();
			assertThat(pending.canTransitionTo(PaymentStatus.DECLINED)).isTrue();
			assertThat(pending.canTransitionTo(PaymentStatus.FAILED)).isTrue();
		}
	}

	@Test
	void finalStatesDoNotGoBackToPending() {
		assertThat(PaymentStatus.PURCHASED.canTransitionTo(PaymentStatus.AWAIT_3DS)).isFalse();
		assertThat(PaymentStatus.DECLINED.canTransitionTo(PaymentStatus.PURCHASED)).isFalse();
		assertThat(PaymentStatus.REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isFalse();
	}

	@Test
	void lateGatewayUpdateIsIgnored() {
		Instant now = Instant.parse("2026-10-16T10:00:00Z");
		Payment payment = Payment.create("pay-12345678", new Money("AED", 100), now, Duration.ofMinutes(2));
		assertThat(payment.transitionTo(PaymentStatus.PURCHASED)).isTrue();

		assertThat(payment.transitionTo(PaymentStatus.AWAIT_3DS)).isFalse();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PURCHASED);
	}

	@Test
	void onlyPendingPaymentsAreScheduledForReconciliation() {
		Instant now = Instant.parse("2026-10-16T10:00:00Z");
		Payment payment = Payment.create("pay-12345678", new Money("AED", 100), now, Duration.ofMinutes(2));
		assertThat(payment.getNextCheckAt()).isEqualTo(now.plus(Duration.ofMinutes(2)));

		payment.fail("HTTP_422", now);

		assertThat(payment.getNextCheckAt()).isNull();
	}

}
