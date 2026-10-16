package com.fazil.payments.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shopper browser details captured by the frontend for EMV 3DS. The IP address is taken from the request
 * instead of trusting the client.
 *
 * @param challengeWindowSize {@code 01}-{@code 05}; {@code 05} is full screen
 */
public record BrowserInfoRequest(@NotBlank @Size(max = 2048) String acceptHeader, @NotNull Boolean javaEnabled,
		@NotBlank @Size(max = 8) String language, @NotBlank @Pattern(regexp = "-?\\d{1,5}") String timeZone,
		@NotBlank @Size(max = 2048) String userAgent, @NotBlank @Pattern(regexp = "\\d{1,2}") String colorDepth,
		@NotBlank @Pattern(regexp = "\\d{1,6}") String screenHeight,
		@NotBlank @Pattern(regexp = "\\d{1,6}") String screenWidth, @NotNull Boolean javascriptEnabled,
		@NotBlank @Pattern(regexp = "0[1-5]") String challengeWindowSize) {

}
