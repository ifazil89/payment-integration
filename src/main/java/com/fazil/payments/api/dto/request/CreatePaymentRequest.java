package com.fazil.payments.api.dto.request;

import com.fazil.payments.api.dto.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(@Valid @NotNull MoneyDto amount, @Valid @NotNull CardRequest card,
		@Valid @NotNull BrowserInfoRequest browserInfo) {

}
