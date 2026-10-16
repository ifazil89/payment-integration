package com.fazil.payments.api;

import java.time.Duration;
import java.time.Instant;

import com.fazil.payments.api.mapper.PaymentApiMapper;
import com.fazil.payments.config.PaymentProperties;
import com.fazil.payments.domain.exception.PaymentNotFoundException;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.gateway.PaymentGateway;
import com.fazil.payments.gateway.exception.WebhookAuthenticationException;
import com.fazil.payments.service.CreatePaymentCommand;
import com.fazil.payments.service.CreatePaymentResult;
import com.fazil.payments.service.PaymentService;
import com.fazil.payments.service.WebhookService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { PaymentController.class, ThreeDsNotificationController.class, WebhookController.class })
@Import(PaymentApiMapper.class)
@ActiveProfiles("test")
class PaymentControllerTest {

	private static final String ID = "pay-12345678";

	private static final String VALID_BODY = """
			{"amount": {"currency": "AED", "value": 100},
			 "card": {"pan": "4111111111111111", "expiry": "2029-04", "cvv": "123", "cardholderName": "John Brown"},
			 "browserInfo": {"acceptHeader": "text/html", "javaEnabled": false, "language": "en", "timeZone": "0",
			                 "userAgent": "Mozilla/5.0", "colorDepth": "24", "screenHeight": "1055",
			                 "screenWidth": "1680", "javascriptEnabled": true, "challengeWindowSize": "05"}}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PaymentService paymentService;

	@MockitoBean
	private WebhookService webhookService;

	@MockitoBean
	private PaymentGateway paymentGateway;

	@Test
	void createPaymentReturns201WithLocationAndUsesRequestIp() throws Exception {
		given(this.paymentService.createPayment(any())).willReturn(new CreatePaymentResult(payment(), false));

		this.mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY)
				.with(request -> {
					request.setRemoteAddr("203.0.113.7");
					return request;
				}))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "http://localhost/api/v1/payments/" + ID))
			.andExpect(header().exists("X-Correlation-Id"))
			.andExpect(jsonPath("$.id").value(ID))
			.andExpect(jsonPath("$.status").value("CREATED"))
			.andExpect(jsonPath("$.amount.value").value(100))
			.andExpect(jsonPath("$.nextAction").doesNotExist());
		then(this.paymentService).should()
			.createPayment(argThat((CreatePaymentCommand command) -> ID.equals(command.idempotencyKey())
					&& "203.0.113.7".equals(command.browserInfo().ip())
					&& command.amount().equals(new Money("AED", 100))));
	}

	@Test
	void replayedPaymentReturns200() throws Exception {
		given(this.paymentService.createPayment(any())).willReturn(new CreatePaymentResult(payment(), true));

		this.mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Location"));
	}

	@Test
	void invalidCardIsRejectedWithoutEchoingIt() throws Exception {
		String body = VALID_BODY.replace("4111111111111111", "4111111111111112");

		this.mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("card.pan"))
			.andExpect(content().string(not(containsString("4111111111111112"))));
		then(this.paymentService).should(never()).createPayment(any());
	}

	@Test
	void malformedIdempotencyKeyIsRejected() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "short")
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));
	}

	@Test
	void missingIdempotencyKeyIsRejected() throws Exception {
		this.mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void unknownPaymentIs404Problem() throws Exception {
		given(this.paymentService.getPayment(ID)).willThrow(new PaymentNotFoundException(ID));

		this.mockMvc.perform(get("/api/v1/payments/" + ID))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Payment " + ID + " not found"));
	}

	@Test
	void threeDsNotificationRedirectsToReturnUrl() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/3ds/notifications/" + ID).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("cres", "cres-value"))
			.andExpect(status().isSeeOther())
			.andExpect(header().string("Location", "http://localhost:3000/payment-result?paymentId=" + ID));
		then(this.paymentService).should().completeChallenge(ID, "cres-value");
	}

	@Test
	void webhookWithWrongSecretIs401AndNotProcessed() throws Exception {
		given(this.paymentGateway.parseWebhook(any(), eq("{}")))
			.willThrow(new WebhookAuthenticationException("bad secret"));

		this.mockMvc.perform(post("/api/v1/webhooks/ni").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		then(this.webhookService).should(never()).processAsync(any());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PaymentProperties.class)
	static class PropertiesConfig {

	}

	private static Payment payment() {
		return Payment.create(ID, new Money("AED", 100), Instant.parse("2026-10-16T10:00:00Z"), Duration.ofMinutes(2));
	}

}
