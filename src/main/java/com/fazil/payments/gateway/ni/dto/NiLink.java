package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiLink(String href) {

}
