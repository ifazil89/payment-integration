package com.fazil.payments.api.mapper;

import com.fazil.payments.api.dto.MoneyDto;
import com.fazil.payments.api.dto.request.BrowserInfoRequest;
import com.fazil.payments.api.dto.request.CardRequest;
import com.fazil.payments.api.dto.request.CreatePaymentRequest;
import com.fazil.payments.api.dto.response.NextActionResponse;
import com.fazil.payments.api.dto.response.PaymentResponse;
import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.domain.model.ThreeDsChallenge;
import com.fazil.payments.service.CreatePaymentCommand;

import org.springframework.stereotype.Component;

/** Converts between the REST contract and the service/domain model, keeping both free of each other's types. */
@Component
public class PaymentApiMapper {

	public CreatePaymentCommand toCommand(String idempotencyKey, CreatePaymentRequest request, String clientIp) {
		return new CreatePaymentCommand(idempotencyKey, toMoney(request.amount()), toCardDetails(request.card()),
				toBrowserInfo(request.browserInfo(), clientIp));
	}

	public Money toMoney(MoneyDto dto) {
		return new Money(dto.currency(), dto.value());
	}

	public PaymentResponse toResponse(Payment payment) {
		return new PaymentResponse(payment.getId(), payment.getStatus(), toDto(payment.getAmount()),
				payment.getRefundedMinor(), payment.getMaskedPan(), payment.getCardBrand(), payment.getFailureCode(),
				toNextAction(payment.getChallenge()), payment.getCreatedAt(), payment.getUpdatedAt());
	}

	private static MoneyDto toDto(Money money) {
		return new MoneyDto(money.currency(), money.minorUnits());
	}

	private static CardDetails toCardDetails(CardRequest card) {
		return new CardDetails(card.pan(), card.expiry(), card.cvv(), card.cardholderName());
	}

	private static BrowserInfo toBrowserInfo(BrowserInfoRequest browser, String clientIp) {
		return new BrowserInfo(browser.acceptHeader(), browser.javaEnabled(), browser.language(), browser.timeZone(),
				browser.userAgent(), browser.colorDepth(), browser.screenHeight(), browser.screenWidth(),
				browser.javascriptEnabled(), clientIp, browser.challengeWindowSize());
	}

	private static NextActionResponse toNextAction(ThreeDsChallenge challenge) {
		return (challenge != null)
				? new NextActionResponse(NextActionResponse.THREE_DS_CHALLENGE, challenge.acsUrl(), challenge.creq())
				: null;
	}

}
