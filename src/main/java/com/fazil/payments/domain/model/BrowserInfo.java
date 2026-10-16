package com.fazil.payments.domain.model;

/** Shopper browser details required for EMV 3DS device authentication. */
public record BrowserInfo(String acceptHeader, boolean javaEnabled, String language, String timeZone,
		String userAgent, String colorDepth, String screenHeight, String screenWidth, boolean javascriptEnabled,
		String ip, String challengeWindowSize) {

}
