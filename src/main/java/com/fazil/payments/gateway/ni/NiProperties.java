package com.fazil.payments.gateway.ni;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * N-Genius connection settings. Secrets come from the environment or a vault and are masked in
 * {@link #toString()}.
 *
 * @param orderAction {@code PURCHASE} captures immediately; {@code AUTH} would need a separate capture call,
 * which this integration does not implement
 * @param tokenRefreshMargin how long before expiry a cached access token is replaced
 * @param retry backoff for the idempotent calls (access token, order lookup); the payment POST is never retried
 */
@ConfigurationProperties("ni")
@Validated
public record NiProperties(@NotBlank String baseUrl, @NotBlank String apiKey, @NotBlank String outletRef,
		@DefaultValue("PURCHASE") @Pattern(regexp = "PURCHASE|SALE") String orderAction,
		@DefaultValue("5s") @NotNull Duration connectTimeout, @DefaultValue("30s") @NotNull Duration readTimeout,
		@DefaultValue("60s") @NotNull Duration tokenRefreshMargin, @Valid @NotNull @DefaultValue Retry retry,
		@Valid @NotNull Webhook webhook) {

	public record Retry(@DefaultValue("2") @PositiveOrZero int maxRetries,
			@DefaultValue("300ms") @NotNull Duration initialDelay,
			@DefaultValue("2.0") @DecimalMin("1.0") double multiplier) {
	}

	public record Webhook(@DefaultValue("X-NI-Webhook-Secret") @NotBlank String headerName,
			@NotBlank String headerValue) {

		@Override
		public String toString() {
			return "Webhook[headerName=" + this.headerName + ", headerValue=***]";
		}

	}

	@Override
	public String toString() {
		return "NiProperties[baseUrl=" + this.baseUrl + ", apiKey=***, outletRef=" + this.outletRef
				+ ", orderAction=" + this.orderAction + ", connectTimeout=" + this.connectTimeout + ", readTimeout="
				+ this.readTimeout + ", tokenRefreshMargin=" + this.tokenRefreshMargin + ", retry=" + this.retry
				+ ", webhook=" + this.webhook + "]";
	}

}
