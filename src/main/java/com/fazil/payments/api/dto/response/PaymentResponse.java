package com.fazil.payments.api.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fazil.payments.api.dto.MoneyDto;
import com.fazil.payments.domain.model.PaymentStatus;

/** @param nextAction present only when the client must act, e.g. run a 3DS challenge */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(String id, PaymentStatus status, MoneyDto amount, long refundedAmount,
		String maskedPan, String cardBrand, String failureCode, NextActionResponse nextAction, Instant createdAt,
		Instant updatedAt) {

}
