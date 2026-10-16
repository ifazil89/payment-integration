package com.fazil.payments.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param publicBaseUrl externally reachable base URL of this service; the 3DS notification URL is built from it
 * @param returnUrl where the shopper's browser is sent after the 3DS challenge (gets {@code ?paymentId=})
 */
@ConfigurationProperties("payments")
@Validated
public record PaymentProperties(@NotBlank String publicBaseUrl, @NotBlank String returnUrl,
		@Valid @NotNull @DefaultValue Reconciliation reconciliation) {

	/**
	 * @param enabled whether this instance runs the reconciliation job
	 * @param interval delay between job runs (read by {@code @Scheduled} through its property placeholder)
	 * @param recheckAfter how long a pending payment waits before the job asks the gateway about it
	 * @param giveUpAfter age after which a still-pending payment is left for manual review
	 */
	public record Reconciliation(@DefaultValue("true") boolean enabled, @DefaultValue("60s") @NotNull Duration interval,
			@DefaultValue("2m") @NotNull Duration recheckAfter, @DefaultValue("24h") @NotNull Duration giveUpAfter,
			@DefaultValue("50") @Positive int batchSize) {
	}

}
