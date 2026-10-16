package com.fazil.payments.service;

import java.time.Clock;
import java.util.Optional;

import com.fazil.payments.common.logging.CorrelationId;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.domain.model.ProcessedWebhookEvent;
import com.fazil.payments.gateway.GatewayWebhookEvent;
import com.fazil.payments.gateway.exception.GatewayException;
import com.fazil.payments.repository.PaymentRepository;
import com.fazil.payments.repository.ProcessedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Processes verified webhooks asynchronously so the endpoint can acknowledge immediately. Webhook payloads are
 * not signed, so they only tell us which payment to look at; the state itself is re-read from the gateway.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

	private final ProcessedWebhookEventRepository processedEventRepository;

	private final PaymentRepository paymentRepository;

	private final PaymentService paymentService;

	private final Clock clock;

	@Async
	public void processAsync(GatewayWebhookEvent event) {
		CorrelationId.runWithCorrelationId("webhook-", () -> {
			try {
				process(event);
			}
			catch (RuntimeException ex) {
				// Not rethrown: there is nobody to report to. The reconciliation job covers pending payments.
				log.error("Processing webhook event {} ({}) failed", event.eventId(), event.eventName(), ex);
			}
		});
	}

	void process(GatewayWebhookEvent event) {
		if (this.processedEventRepository.existsById(event.eventId())) {
			log.info("Skipping duplicate webhook event {} ({})", event.eventId(), event.eventName());
			return;
		}
		Optional<Payment> payment = findPayment(event);
		if (payment.isEmpty()) {
			log.warn("Webhook event {} ({}) for order {} matches no payment", event.eventId(), event.eventName(),
					event.orderReference());
			return;
		}
		String paymentId = payment.get().getId();
		log.info("Webhook event {} ({}) for payment {}", event.eventId(), event.eventName(), paymentId);
		try {
			this.paymentService.reconcile(paymentId);
		}
		catch (GatewayException ex) {
			log.warn("Could not confirm webhook event {} with the gateway, scheduling a check: {}", event.eventId(),
					ex.getMessage());
			this.paymentService.scheduleCheck(paymentId, this.clock.instant());
			return;
		}
		this.processedEventRepository
			.save(new ProcessedWebhookEvent(event.eventId(), event.eventName(), this.clock.instant()));
	}

	/** By id first (real-time get), then by order reference (search, near-real-time). */
	private Optional<Payment> findPayment(GatewayWebhookEvent event) {
		if (event.merchantReference() != null) {
			Optional<Payment> byId = this.paymentRepository.findById(event.merchantReference());
			if (byId.isPresent()) {
				return byId;
			}
		}
		return (event.orderReference() != null)
				? this.paymentRepository.findFirstByGatewayOrderReference(event.orderReference()) : Optional.empty();
	}

}
