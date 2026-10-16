package com.fazil.payments.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fazil.payments.config.PaymentProperties;
import com.fazil.payments.domain.exception.IdempotencyConflictException;
import com.fazil.payments.domain.exception.PaymentStateException;
import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.GatewayLinks;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.domain.model.ThreeDsChallenge;
import com.fazil.payments.gateway.PaymentGateway;
import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.GatewayRejectedException;
import com.fazil.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

	private static final String ID = "pay-12345678";

	private static final Money AMOUNT = new Money("AED", 100);

	private static final GatewayLinks AWAIT_LINKS = new GatewayLinks("https://ni/self", "https://ni/auth",
			"https://ni/challenge-response", null);

	private final PaymentGateway gateway = mock(PaymentGateway.class);

	private final PaymentRepository paymentRepository = InMemoryPaymentRepository.create();

	private PaymentService service;

	@BeforeEach
	void setUp() {
		PaymentProperties properties = new PaymentProperties("https://pay.example/", "https://shop.example/return",
				new PaymentProperties.Reconciliation(true, Duration.ofSeconds(60), Duration.ofMinutes(2),
						Duration.ofHours(24), 50));
		this.service = new PaymentService(this.paymentRepository, this.gateway, properties,
				Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void frictionless3dsEndsPurchased() {
		when(this.gateway.createPayment(any())).thenReturn(result(PaymentStatus.AWAIT_3DS, AWAIT_LINKS, null));
		when(this.gateway.authenticate3ds(any(), any(), anyString()))
			.thenReturn(result(PaymentStatus.PURCHASED, null, null));

		CreatePaymentResult result = this.service.createPayment(command());

		assertThat(result.replayed()).isFalse();
		assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PURCHASED);
		assertThat(result.payment().getNextCheckAt()).isNull();
		verify(this.gateway).authenticate3ds(any(), any(), eq("https://pay.example/api/v1/3ds/notifications/" + ID));
	}

	@Test
	void challengeIsReturnedToTheClient() {
		ThreeDsChallenge challenge = new ThreeDsChallenge("https://acs.example", "creq-value");
		when(this.gateway.createPayment(any())).thenReturn(result(PaymentStatus.AWAIT_3DS, AWAIT_LINKS, null));
		when(this.gateway.authenticate3ds(any(), any(), anyString()))
			.thenReturn(result(PaymentStatus.AWAIT_3DS, null, challenge));

		Payment payment = this.service.createPayment(command()).payment();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AWAIT_3DS);
		assertThat(payment.getChallenge()).isEqualTo(challenge);
		verify(this.gateway, never()).getOrder(anyString());
	}

	@Test
	void challengeResultCompletesThePayment() {
		challengeIsReturnedToTheClient();
		when(this.gateway.completeChallenge(any(), eq("cres-value")))
			.thenReturn(result(PaymentStatus.PURCHASED, null, null));

		Payment payment = this.service.completeChallenge(ID, "cres-value");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PURCHASED);
		assertThat(payment.getChallenge()).isNull();
	}

	@Test
	void repeatedChallengeNotificationIsNotSentToTheGatewayAgain() {
		challengeResultCompletesThePayment();

		this.service.completeChallenge(ID, "cres-value");

		verify(this.gateway, times(1)).completeChallenge(any(), anyString());
	}

	@Test
	void rejectedChallengeIsResolvedFromOrderStatus() {
		challengeIsReturnedToTheClient();
		when(this.gateway.completeChallenge(any(), anyString()))
			.thenThrow(new GatewayRejectedException("3ds-challenge-response", 400, null));
		when(this.gateway.getOrder("order-ref-1")).thenReturn(result(PaymentStatus.FAILED, null, null));

		assertThat(this.service.completeChallenge(ID, "cres-value").getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	void timeoutMarksUnknownAndNeverRetries() {
		when(this.gateway.createPayment(any()))
			.thenThrow(new GatewayOutcomeUnknownException("create-payment", "I/O failure", null));

		Payment payment = this.service.createPayment(command()).payment();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getNextCheckAt()).isNotNull();
		verify(this.gateway, times(1)).createPayment(any());
	}

	@Test
	void rejectionMarksFailedWithReason() {
		when(this.gateway.createPayment(any()))
			.thenThrow(new GatewayRejectedException("create-payment", 422, "invalidPan"));

		Payment payment = this.service.createPayment(command()).payment();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailureCode()).isEqualTo("invalidPan");
	}

	@Test
	void sameIdempotencyKeyDoesNotChargeTwice() {
		frictionless3dsEndsPurchased();

		CreatePaymentResult replay = this.service.createPayment(command());

		assertThat(replay.replayed()).isTrue();
		assertThat(replay.payment().getStatus()).isEqualTo(PaymentStatus.PURCHASED);
		verify(this.gateway, times(1)).createPayment(any());
	}

	@Test
	void sameIdempotencyKeyWithDifferentAmountIsRejected() {
		frictionless3dsEndsPurchased();

		assertThatThrownBy(() -> this.service.createPayment(new CreatePaymentCommand(ID, new Money("AED", 200),
				card(), browser())))
			.isInstanceOf(IdempotencyConflictException.class);
	}

	@Test
	void refundUsesLinkFromFreshOrderStatus() {
		frictionless3dsEndsPurchased();
		GatewayLinks refundLinks = new GatewayLinks(null, null, null, "https://ni/refund");
		when(this.gateway.getOrder("order-ref-1")).thenReturn(result(PaymentStatus.PURCHASED, refundLinks, null));
		when(this.gateway.refund(any(), eq(new Money("AED", 40))))
			.thenReturn(result(PaymentStatus.PARTIALLY_REFUNDED, null, null));

		Payment payment = this.service.refund(ID, new Money("AED", 40));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
		assertThat(payment.getRefundedMinor()).isEqualTo(40);
		verify(this.gateway).refund(argThat(links -> "https://ni/refund".equals(links.refund())), any());
	}

	@Test
	void refundAboveRemainingAmountIsRefused() {
		frictionless3dsEndsPurchased();

		assertThatThrownBy(() -> this.service.refund(ID, new Money("AED", 101)))
			.isInstanceOf(PaymentStateException.class);
		verify(this.gateway, never()).refund(any(), any());
	}

	private static GatewayPaymentResult result(PaymentStatus status, GatewayLinks links, ThreeDsChallenge challenge) {
		return new GatewayPaymentResult("order-ref-1", "pay-ref-1", status, status.name(), links, challenge,
				"230377******0408", "VISA", null);
	}

	private static CreatePaymentCommand command() {
		return new CreatePaymentCommand(ID, AMOUNT, card(), browser());
	}

	private static CardDetails card() {
		return new CardDetails("2303779999000275", "2029-04", "123", "John Brown");
	}

	private static BrowserInfo browser() {
		return new BrowserInfo("application/json", false, "en", "0", "Mozilla/5.0", "24", "1055", "1680", true,
				"203.0.113.7", "05");
	}

}
