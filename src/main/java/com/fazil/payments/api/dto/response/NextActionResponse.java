package com.fazil.payments.api.dto.response;

/**
 * What the client must do next. For {@link #THREE_DS_CHALLENGE}, the shopper's browser form-POSTs a field named
 * {@code creq} with value {@link #creq()} to {@link #acsUrl()}.
 */
public record NextActionResponse(String type, String acsUrl, String creq) {

	public static final String THREE_DS_CHALLENGE = "THREE_DS_CHALLENGE";

}
