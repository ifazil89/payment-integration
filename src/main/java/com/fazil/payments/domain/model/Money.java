package com.fazil.payments.domain.model;

/** Amount in minor units (100 = 1.00 AED) with an ISO 4217 currency code. */
public record Money(String currency, long minorUnits) {

}
