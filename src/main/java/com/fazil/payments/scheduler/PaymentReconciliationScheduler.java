package com.fazil.payments.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.fazil.payments.common.logging.CorrelationId;
import com.fazil.payments.config.PaymentProperties;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.gateway.exception.GatewayException;
import com.fazil.payments.repository.PaymentRepository;
import com.fazil.payments.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the cases webhooks and synchronous responses miss: lost responses ({@code UNKNOWN}),
 * abandoned 3DS challenges, crashes between saving and calling the gateway, and refunds with unknown
 * outcome. Webhooks are never retried by N-Genius, so this job is what guarantees eventual consistency.
 * Running it on several instances at once is harmless: reads are idempotent and writes are version-checked.
 */
@Component
@ConditionalOnBooleanProperty(name = "payments.reconciliation.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

	private final PaymentRepository paymentRepository;

	private final PaymentService paymentService;

	private final PaymentProperties properties;

	private final Clock clock;

	@Scheduled(fixedDelayString = "${payments.reconciliation.interval:60s}",
			initialDelayString = "${payments.reconciliation.interval:60s}")
	public void reconcileDuePayments() {
		CorrelationId.runWithCorrelationId("reconcile-", () -> {
			try {
				Instant now = this.clock.instant();
				List<Payment> due = this.paymentRepository.findByNextCheckAtLessThanEqual(now,
						PageRequest.ofSize(this.properties.reconciliation().batchSize()));
				if (!due.isEmpty()) {
					log.info("Reconciling {} payment(s)", due.size());
				}
				due.forEach(payment -> reconcile(payment, now));
			}
			catch (RuntimeException ex) {
				log.error("Reconciliation run failed", ex);
			}
		});
	}

	private void reconcile(Payment payment, Instant now) {
		String id = payment.getId();
		PaymentProperties.Reconciliation config = this.properties.reconciliation();
		Instant nextCheck = now.plus(config.recheckAfter());
		try {
			if (payment.getCreatedAt().plus(config.giveUpAfter()).isBefore(now)) {
				log.error("Payment {} still {} after {}; stopping automatic checks, needs manual review", id,
						payment.getStatus(), config.giveUpAfter());
				this.paymentRepository.update(id, Payment::stopChecking);
				return;
			}
			if (payment.getGatewayOrderReference() == null) {
				// Saved but the gateway never answered: the outcome cannot be looked up without an order reference.
				// It may still arrive via webhook (matched on merchant reference).
				if (payment.getStatus() == PaymentStatus.CREATED) {
					log.error("Payment {} has no gateway order reference; marking UNKNOWN", id);
					this.paymentRepository.update(id, p -> p.markUnknown(now, config.recheckAfter()));
				}
				else {
					this.paymentService.scheduleCheck(id, nextCheck);
				}
				return;
			}
			Payment updated = this.paymentService.reconcile(id);
			if (updated.getStatus().isPending()) {
				this.paymentService.scheduleCheck(id, nextCheck);
			}
		}
		catch (GatewayException ex) {
			log.warn("Reconciling payment {} failed, will retry: {}", id, ex.getMessage());
			this.paymentService.scheduleCheck(id, nextCheck);
		}
		catch (RuntimeException ex) {
			log.error("Reconciling payment {} failed unexpectedly", id, ex);
		}
	}

}
