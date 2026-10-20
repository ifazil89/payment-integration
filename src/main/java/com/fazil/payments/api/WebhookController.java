package com.fazil.payments.api;

import com.fazil.payments.gateway.GatewayWebhookEvent;
import com.fazil.payments.gateway.PaymentGateway;
import com.fazil.payments.service.WebhookService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * N-Genius webhook receiver. Verifies the shared-secret header, then acknowledges at once; N-Genius gives up
 * after 15 seconds and never retries, so processing happens asynchronously. Authentication and payload failures
 * are mapped to 401/400 by the global exception handler.
 */
@RestController
@RequestMapping("/api/v1/webhooks/ni")
@RequiredArgsConstructor
@Slf4j
@Hidden
public class WebhookController {

	private final PaymentGateway paymentGateway;

	private final WebhookService webhookService;

	@PostMapping
	public ResponseEntity<Void> receiveWebhook(@RequestHeader HttpHeaders headers, @RequestBody String body) {
		GatewayWebhookEvent event = this.paymentGateway.parseWebhook(headers, body);
		log.info("Accepted webhook event {} ({})", event.eventId(), event.eventName());
		try {
			this.webhookService.processAsync(event);
		}
		catch (TaskRejectedException ex) {
			// Still acknowledge: N-Genius never retries, so waiting for capacity past its 15 s timeout would lose
			// the event just the same. The reconciliation job still resolves pending payments.
			log.error("Dropped webhook event {} ({}): processing capacity exhausted", event.eventId(),
					event.eventName());
		}
		return ResponseEntity.ok().build();
	}

}
