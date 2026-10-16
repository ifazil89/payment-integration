package com.fazil.payments.gateway.ni.dto;

public record NiBrowserInfo(String browserAcceptHeader, boolean browserJavaEnabled, String browserLanguage,
		String browserTZ, String browserUserAgent, String browserColorDepth, String browserScreenHeight,
		String browserScreenWidth, boolean browserJavascriptEnabled, String browserIP, String challengeWindowSize) {

}
