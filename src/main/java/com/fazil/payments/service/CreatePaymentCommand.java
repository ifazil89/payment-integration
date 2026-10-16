package com.fazil.payments.service;

import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.Money;

/** {@link CardDetails#toString()} is masked, so this record is safe to log. */
public record CreatePaymentCommand(String idempotencyKey, Money amount, CardDetails card, BrowserInfo browserInfo) {

}
