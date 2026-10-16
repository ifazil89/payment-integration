package com.fazil.payments.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * @param currency ISO 4217 code
 * @param value minor units: 100 = 1.00 AED
 */
public record MoneyDto(@NotNull @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull @Positive Long value) {

}
