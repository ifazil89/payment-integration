package com.fazil.payments.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import com.fazil.payments.config.PaymentProperties;
import com.fazil.payments.domain.exception.IdempotencyConflictException;
import com.fazil.payments.domain.exception.PaymentNotFoundException;
import com.fazil.payments.domain.exception.PaymentStateException;
import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.gateway.GatewayPaymentRequest;
import com.fazil.payments.gateway.PaymentGateway;
import com.fazil.payments.gateway.exception.GatewayException;
import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.GatewayRejectedException;
import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import com.fazil.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * One-stage card payment flow: create the payment, run EMV 3DS when required, complete the challenge, and
 * reconcile with the gateway whenever the outcome is not certain.
 *
 * <p>
 * The local record is always written before the gateway is called, and the payment call is never retried: a
 * lost response marks the payment {@code UNKNOWN} for reconciliation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

	/** Must match the mapping of {@code ThreeDsNotificationController}. */
	static final String THREE_DS_NOTIFICATION_PATH = "/api/v1/3ds/notifications/{paymentId}";

	static final String FAILURE_GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE";

	private final PaymentRepository paymentRepository;

	private final PaymentGateway paymentGateway;

	private final PaymentProperties properties;

	private final Clock clock;

	public CreatePaymentResult createPayment(CreatePaymentCommand command) {
		Payment draft = Payment.create(command.idempotencyKey(), command.amount(), now(), recheckAfter());
		Payment existing = this.paymentRepository.insertIfAbsent(draft).orElse(null);
		if (existing != null) {
			if (!existing.getAmount().equals(command.amount())) {
				throw new IdempotencyConflictException(existing.getId());
			}
			log.info("Replaying payment {} for repeated idempotency key (status {})", existing.getId(),
					existing.getStatus());
			return new CreatePaymentResult(existing, true);
		}
		String id = draft.getId();
		log.info("Payment {} created: {} {} minor units", id, command.amount().currency(),
				command.amount().minorUnits());

		GatewayPaymentResult result;
		try {
			result = this.paymentGateway.createPayment(new GatewayPaymentRequest(id, command.amount(), command.card()));
		}
		catch (GatewayRejectedException ex) {
			log.warn("Payment {} rejected by gateway: {}", id, ex.getMessage());
			return new CreatePaymentResult(update(id, p -> p.fail(ex.getReasonCode(), now())), false);
		}
		catch (GatewayUnavailableException ex) {
			log.error("Payment {} not submitted, gateway unavailable: {}", id, ex.getMessage());
			return new CreatePaymentResult(update(id, p -> p.fail(FAILURE_GATEWAY_UNAVAILABLE, now())), false);
		}
		catch (GatewayOutcomeUnknownException ex) {
			log.error("Payment {} outcome unknown, will reconcile: {}", id, ex.getMessage());
			return new CreatePaymentResult(update(id, p -> p.markUnknown(now(), recheckAfter())), false);
		}

		Payment payment = applyGatewayResult(id, result);
		if (payment.getStatus() == PaymentStatus.AWAIT_3DS) {
			payment = authenticate3ds(payment, command.browserInfo());
		}
		return new CreatePaymentResult(payment, false);
	}

	/**
	 * Handles the ACS callback. Safe to call more than once: only a payment still awaiting 3DS is sent to the
	 * gateway. The {@code cres} value is passed through unchanged.
	 */
	public Payment completeChallenge(String paymentId, String cres) {
		Payment payment = getPayment(paymentId);
		if (payment.getStatus() != PaymentStatus.AWAIT_3DS) {
			log.info("Ignoring 3DS notification for payment {} in status {}", paymentId, payment.getStatus());
			return payment;
		}
		if (payment.getLinks().threeDsChallengeResponse() == null) {
			log.warn("Payment {} has no challenge-response link, reconciling instead", paymentId);
			return reconcileQuietly(paymentId);
		}
		GatewayPaymentResult result;
		try {
			result = this.paymentGateway.completeChallenge(payment.getLinks(), cres);
		}
		catch (GatewayRejectedException ex) {
			log.info("Gateway rejected 3DS challenge result for payment {}: {}", paymentId, ex.getMessage());
			return reconcileQuietly(paymentId);
		}
		catch (GatewayException ex) {
			log.warn("Completing 3DS challenge failed for payment {}: {}", paymentId, ex.getMessage());
			return reconcileQuietly(paymentId);
		}
		Payment updated = applyGatewayResult(paymentId, result);
		return (updated.getStatus() == PaymentStatus.AWAIT_3DS) ? reconcileQuietly(paymentId) : updated;
	}

	public Payment getPayment(String paymentId) {
		return this.paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
	}

	/**
	 * Pulls the authoritative state from the gateway.
	 * @throws GatewayException if the gateway cannot be reached
	 */
	public Payment reconcile(String paymentId) {
		Payment payment = getPayment(paymentId);
		if (payment.getGatewayOrderReference() == null) {
			log.warn("Payment {} has no gateway order reference; cannot reconcile automatically", paymentId);
			return payment;
		}
		GatewayPaymentResult result = this.paymentGateway.getOrder(payment.getGatewayOrderReference());
		return applyGatewayResult(paymentId, result);
	}

	public Payment refund(String paymentId, Money amount) {
		Payment payment = getPayment(paymentId);
		validateRefund(payment, amount);
		// The refund link comes from the current order status, as the spec requires.
		payment = reconcile(paymentId);
		if (payment.getLinks().refund() == null) {
			throw new PaymentStateException("Gateway does not offer a refund for this payment yet");
		}
		GatewayPaymentResult result;
		try {
			result = this.paymentGateway.refund(payment.getLinks(), amount);
		}
		catch (GatewayOutcomeUnknownException ex) {
			log.error("Refund of {} on payment {} has unknown outcome, scheduling a check: {}", amount.minorUnits(),
					paymentId, ex.getMessage());
			update(paymentId, p -> p.scheduleCheck(now().plus(recheckAfter())));
			throw ex;
		}
		log.info("Refunded {} {} minor units on payment {}", amount.currency(), amount.minorUnits(), paymentId);
		return update(paymentId, p -> {
			p.addRefund(amount.minorUnits());
			p.apply(result, now(), recheckAfter());
		});
	}

	/** Moves the reconciliation check forward, e.g. after a webhook or a failed gateway call. */
	public void scheduleCheck(String paymentId, Instant at) {
		update(paymentId, p -> p.scheduleCheck(at));
	}

	private static void validateRefund(Payment payment, Money amount) {
		if (!payment.getCurrency().equals(amount.currency())) {
			throw new PaymentStateException("Refund currency must be " + payment.getCurrency());
		}
		if (!payment.getStatus().isRefundable()) {
			throw new PaymentStateException("Payment in status " + payment.getStatus() + " cannot be refunded");
		}
		if (amount.minorUnits() > payment.getRefundableMinor()) {
			throw new PaymentStateException(
					"Refund exceeds the remaining " + payment.getRefundableMinor() + " minor units");
		}
	}

	private Payment authenticate3ds(Payment payment, BrowserInfo browserInfo) {
		String id = payment.getId();
		if (payment.getLinks().threeDsAuthentication() == null) {
			log.warn("Payment {} awaits 3DS but no authentication link was returned, reconciling", id);
			return reconcileQuietly(id);
		}
		GatewayPaymentResult result;
		try {
			result = this.paymentGateway.authenticate3ds(payment.getLinks(), browserInfo, notificationUrl(id));
		}
		catch (GatewayException ex) {
			log.warn("3DS authentication failed for payment {}: {}", id, ex.getMessage());
			return reconcileQuietly(id);
		}
		Payment updated = applyGatewayResult(id, result);
		if (result.challenge() != null) {
			log.info("Payment {} requires a 3DS challenge", id);
			return updated;
		}
		// Frictionless: the state should already be final; confirm if it is not.
		return (updated.getStatus() == PaymentStatus.AWAIT_3DS) ? reconcileQuietly(id) : updated;
	}

	/** Reconciles, falling back to the stored payment (and the scheduled job) if the gateway is unreachable. */
	private Payment reconcileQuietly(String paymentId) {
		try {
			return reconcile(paymentId);
		}
		catch (GatewayException ex) {
			log.warn("Reconciliation of payment {} deferred: {}", paymentId, ex.getMessage());
			return getPayment(paymentId);
		}
	}

	private Payment applyGatewayResult(String paymentId, GatewayPaymentResult result) {
		return update(paymentId, p -> p.apply(result, now(), recheckAfter()));
	}

	private Payment update(String paymentId, Consumer<Payment> mutation) {
		return this.paymentRepository.update(paymentId, mutation);
	}

	private String notificationUrl(String paymentId) {
		return UriComponentsBuilder.fromUriString(this.properties.publicBaseUrl())
			.path(THREE_DS_NOTIFICATION_PATH)
			.buildAndExpand(paymentId)
			.toUriString();
	}

	private Duration recheckAfter() {
		return this.properties.reconciliation().recheckAfter();
	}

	private Instant now() {
		return this.clock.instant();
	}

}
