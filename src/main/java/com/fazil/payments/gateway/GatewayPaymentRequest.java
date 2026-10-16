package com.fazil.payments.gateway;

import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.Money;

/**
 * @param merchantReference our payment id, sent to the gateway so the order can be matched back to us
 */
public record GatewayPaymentRequest(String merchantReference, Money amount, CardDetails card) {

}
