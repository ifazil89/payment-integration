package com.fazil.payments.service;

import com.fazil.payments.domain.model.Payment;

/** @param replayed true when an earlier request with the same idempotency key already created the payment */
public record CreatePaymentResult(Payment payment, boolean replayed) {

}
