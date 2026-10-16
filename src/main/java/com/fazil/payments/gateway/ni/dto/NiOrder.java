package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NiOrder(String action, NiAmount amount, String merchantOrderReference) {

}
