package com.fazil.payments.api;

import java.net.URI;

import com.fazil.payments.api.validation.PaymentId;
import com.fazil.payments.config.PaymentProperties;
import com.fazil.payments.domain.exception.PaymentNotFoundException;
import com.fazil.payments.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 3DS {@code notificationURL}: the ACS form-POSTs {@code cres} here from the shopper's browser. The browser is
 * then redirected to the configured return URL, which must read the outcome from the payments API; the
 * redirect itself is not proof of payment.
 */
@RestController
@RequestMapping("/api/v1/3ds/notifications")
@RequiredArgsConstructor
@Slf4j
@Hidden
public class ThreeDsNotificationController {

	private final PaymentService paymentService;

	private final PaymentProperties properties;

	@PostMapping(path = "/{paymentId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Void> handleChallengeResult(@PathVariable @PaymentId String paymentId,
			@RequestParam("cres") String cres) {
		completeChallenge(paymentId, cres);
		URI returnUri = UriComponentsBuilder.fromUriString(this.properties.returnUrl())
			.queryParam("paymentId", paymentId)
			.build()
			.toUri();
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(returnUri).build();
	}

	/** The shopper must not be stranded on an error page; the return page polls the status instead. */
	private void completeChallenge(String paymentId, String cres) {
		try {
			this.paymentService.completeChallenge(paymentId, cres);
		}
		catch (PaymentNotFoundException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.error("Completing 3DS for payment {} failed; redirecting shopper anyway", paymentId, ex);
		}
	}

}
