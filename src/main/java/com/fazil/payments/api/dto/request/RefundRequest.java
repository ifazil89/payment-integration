package com.fazil.payments.api.dto.request;

import com.fazil.payments.api.dto.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RefundRequest(@Valid @NotNull MoneyDto amount) {

}
