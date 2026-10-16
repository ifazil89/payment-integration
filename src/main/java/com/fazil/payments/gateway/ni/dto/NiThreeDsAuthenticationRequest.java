package com.fazil.payments.gateway.ni.dto;

public record NiThreeDsAuthenticationRequest(String deviceChannel, String threeDSCompInd, String notificationURL,
		NiBrowserInfo browserInfo) {

}
