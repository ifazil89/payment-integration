package com.fazil.payments.gateway.ni.dto;

public record NiCardPaymentRequest(NiOrder order, NiCardPayment payment) {

}
