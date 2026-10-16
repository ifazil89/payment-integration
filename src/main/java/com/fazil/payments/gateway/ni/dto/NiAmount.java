package com.fazil.payments.gateway.ni.dto;

/** @param value minor units */
public record NiAmount(String currencyCode, long value) {

}
