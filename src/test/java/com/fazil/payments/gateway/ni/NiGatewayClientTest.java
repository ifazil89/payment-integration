package com.fazil.payments.gateway.ni;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import com.fazil.payments.domain.model.BrowserInfo;
import com.fazil.payments.domain.model.CardDetails;
import com.fazil.payments.domain.model.GatewayLinks;
import com.fazil.payments.domain.model.GatewayPaymentResult;
import com.fazil.payments.domain.model.Money;
import com.fazil.payments.domain.model.PaymentStatus;
import com.fazil.payments.gateway.GatewayPaymentRequest;
import com.fazil.payments.gateway.GatewayWebhookEvent;
import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.GatewayRejectedException;
import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import com.fazil.payments.gateway.exception.InvalidWebhookException;
import com.fazil.payments.gateway.exception.WebhookAuthenticationException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiGatewayClientTest {

	private static final String TOKEN_PATH = "/identity/auth/access-token";

	private static final String PAYMENT_PATH = "/transactions/outlets/outlet-1/payment/card";

	private static final String ORDER_PATH = "/transactions/outlets/outlet-1/orders/order-ref-1";

	private static final String AUTH_PATH = "/transactions/outlets/outlet-1/orders/order-ref-1/payments/pay-ref-1/card/3ds2/authentications";

	@RegisterExtension
	static WireMockExtension ni = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	private final JsonMapper json = JsonMapper.builder().build();

	private NiGatewayClient client;

	@BeforeEach
	void setUp() {
		this.client = client(ni.baseUrl());
		ni.stubFor(post(TOKEN_PATH).willReturn(okJson(fixture("token.json"))));
	}

	@Test
	void createPaymentSendsOrderAndCardAndParsesAwait3ds() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(okJson(fixture("payment-await-3ds.json"))));

		GatewayPaymentResult result = this.client.createPayment(paymentRequest());

		assertThat(result.status()).isEqualTo(PaymentStatus.AWAIT_3DS);
		assertThat(result.orderReference()).isEqualTo("order-ref-1");
		assertThat(result.paymentReference()).isEqualTo("pay-ref-1");
		assertThat(result.maskedPan()).isEqualTo("230377******0408");
		assertThat(result.links().threeDsAuthentication()).isEqualTo(ni.baseUrl() + AUTH_PATH);
		ni.verify(postRequestedFor(urlEqualTo(PAYMENT_PATH))
			.withHeader("Authorization", equalTo("Bearer test-access-token"))
			.withHeader("Content-Type", equalTo("application/vnd.ni-payment.v2+json"))
			.withHeader("Accept", equalTo("application/vnd.ni-payment.v2+json"))
			.withRequestBody(equalToJson("""
					{"order": {"action": "PURCHASE", "amount": {"currencyCode": "AED", "value": 100},
					           "merchantOrderReference": "pay-12345678"},
					 "payment": {"pan": "2303779999000408", "expiry": "2029-04", "cvv": "123",
					             "cardholderName": "John Brown"}}""")));
		ni.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
			.withHeader("Authorization", equalTo("Basic test-api-key"))
			.withHeader("Content-Type", equalTo("application/vnd.ni-identity.v1+json")));
	}

	@Test
	void accessTokenIsCachedBetweenCalls() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(okJson(fixture("payment-await-3ds.json"))));

		this.client.createPayment(paymentRequest());
		this.client.createPayment(paymentRequest());

		ni.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
	}

	@Test
	void expiredTokenIsRefreshedOnceAfter401() {
		ni.stubFor(post(PAYMENT_PATH).inScenario("401")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(401))
			.willSetStateTo("refreshed"));
		ni.stubFor(post(PAYMENT_PATH).inScenario("401")
			.whenScenarioStateIs("refreshed")
			.willReturn(okJson(fixture("payment-await-3ds.json"))));

		GatewayPaymentResult result = this.client.createPayment(paymentRequest());

		assertThat(result.status()).isEqualTo(PaymentStatus.AWAIT_3DS);
		ni.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
		ni.verify(2, postRequestedFor(urlEqualTo(PAYMENT_PATH)));
	}

	@Test
	void readTimeoutOnPaymentIsOutcomeUnknownAndNotRetried() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(okJson(fixture("payment-await-3ds.json")).withFixedDelay(1500)));

		assertThatThrownBy(() -> this.client.createPayment(paymentRequest()))
			.isInstanceOf(GatewayOutcomeUnknownException.class);
		ni.verify(1, postRequestedFor(urlEqualTo(PAYMENT_PATH)));
	}

	@Test
	void serverErrorOnPaymentIsOutcomeUnknown() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(aResponse().withStatus(502)));

		assertThatThrownBy(() -> this.client.createPayment(paymentRequest()))
			.isInstanceOf(GatewayOutcomeUnknownException.class);
	}

	@Test
	void clientErrorIsRejectionWithSanitisedCodeAndNoBodyInMessage() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(aResponse().withStatus(422)
			.withHeader("Content-Type", "application/json")
			.withBody(fixture("error-422.json"))));

		assertThatThrownBy(() -> this.client.createPayment(paymentRequest()))
			.isInstanceOfSatisfying(GatewayRejectedException.class, ex -> {
				assertThat(ex.getHttpStatus()).isEqualTo(422);
				assertThat(ex.getReasonCode()).isEqualTo("invalidPan");
				assertThat(ex).hasMessageNotContaining("4111");
			});
	}

	@Test
	void unreachableGatewayIsUnavailable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		NiGatewayClient unreachable = client("http://localhost:" + closedPort);

		assertThatThrownBy(() -> unreachable.createPayment(paymentRequest()))
			.isInstanceOf(GatewayUnavailableException.class);
	}

	@Test
	void challengeBuildsBase64UrlCreqFromThreeDs2Block() {
		ni.stubFor(post(AUTH_PATH).willReturn(okJson(fixture("3ds-authentication-challenge.json"))));

		GatewayPaymentResult result = this.client.authenticate3ds(links(), browserInfo(),
				"https://pay.example/api/v1/3ds/notifications/pay-12345678");

		assertThat(result.status()).isEqualTo(PaymentStatus.AWAIT_3DS);
		assertThat(result.challenge()).isNotNull();
		assertThat(result.challenge().acsUrl()).isEqualTo("https://acs.example/challenge");
		String creq = new String(Base64.getUrlDecoder().decode(result.challenge().creq()), StandardCharsets.UTF_8);
		assertThat(this.json.readTree(creq)).isEqualTo(this.json.readTree("""
				{"threeDSServerTransID": "server-trans-1", "acsTransID": "acs-trans-1", "challengeWindowSize": "05",
				 "messageType": "CReq", "messageVersion": "2.1.0"}"""));
		ni.verify(postRequestedFor(urlEqualTo(AUTH_PATH))
			.withRequestBody(matchingJsonPath("$.deviceChannel", equalTo("BRW")))
			.withRequestBody(matchingJsonPath("$.threeDSCompInd", equalTo("Y")))
			.withRequestBody(matchingJsonPath("$.notificationURL",
					equalTo("https://pay.example/api/v1/3ds/notifications/pay-12345678")))
			.withRequestBody(matchingJsonPath("$.browserInfo.browserIP", equalTo("203.0.113.7"))));
	}

	@Test
	void frictionlessAuthenticationReturnsFinalStateWithoutChallenge() {
		ni.stubFor(post(AUTH_PATH).willReturn(okJson(fixture("3ds-authentication-frictionless.json"))));

		GatewayPaymentResult result = this.client.authenticate3ds(links(), browserInfo(), "https://n.example");

		assertThat(result.status()).isEqualTo(PaymentStatus.PURCHASED);
		assertThat(result.challenge()).isNull();
	}

	@Test
	void getOrderRetriesTransientFailuresAndReadsEmbeddedPayment() {
		ni.stubFor(get(ORDER_PATH).inScenario("flaky")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("up"));
		ni.stubFor(get(ORDER_PATH).inScenario("flaky")
			.whenScenarioStateIs("up")
			.willReturn(okJson(fixture("order-purchased.json"))));

		GatewayPaymentResult result = this.client.getOrder("order-ref-1");

		assertThat(result.status()).isEqualTo(PaymentStatus.PURCHASED);
		assertThat(result.orderReference()).isEqualTo("order-ref-1");
		assertThat(result.links().refund()).endsWith("/captures/cap-1/refund");
		ni.verify(2, getRequestedFor(urlEqualTo(ORDER_PATH)));
	}

	@Test
	void refundFollowsTheRefundLink() {
		String refundPath = "/transactions/outlets/outlet-1/orders/order-ref-1/payments/pay-ref-1/captures/cap-1/refund";
		ni.stubFor(post(refundPath).willReturn(okJson("""
				{"reference": "pay-ref-1", "orderReference": "order-ref-1", "state": "REFUNDED"}""")));

		GatewayPaymentResult result = this.client.refund(
				new GatewayLinks(null, null, null, ni.baseUrl() + refundPath), new Money("AED", 100));

		assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
		ni.verify(postRequestedFor(urlPathMatching(".*/refund"))
			.withRequestBody(equalToJson("{\"amount\": {\"currencyCode\": \"AED\", \"value\": 100}}")));
	}

	@Test
	void webhookRequiresTheSharedSecret() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-NI-Webhook-Secret", "wrong");

		assertThatThrownBy(() -> this.client.parseWebhook(headers, "{}"))
			.isInstanceOf(WebhookAuthenticationException.class);
		assertThatThrownBy(() -> this.client.parseWebhook(new HttpHeaders(), "{}"))
			.isInstanceOf(WebhookAuthenticationException.class);
	}

	@Test
	void secondUnauthorizedAfterRefreshIsUnavailable() {
		ni.stubFor(post(PAYMENT_PATH).willReturn(aResponse().withStatus(401)));

		assertThatThrownBy(() -> this.client.createPayment(paymentRequest()))
			.isInstanceOf(GatewayUnavailableException.class);
		ni.verify(2, postRequestedFor(urlEqualTo(PAYMENT_PATH)));
	}

	@Test
	void webhookWithoutEventIdIsInvalid() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-NI-Webhook-Secret", "webhook-secret");

		assertThatThrownBy(() -> this.client.parseWebhook(headers, "{\"eventName\": \"PURCHASED\"}"))
			.isInstanceOf(InvalidWebhookException.class);
		assertThatThrownBy(() -> this.client.parseWebhook(headers, "not json"))
			.isInstanceOf(InvalidWebhookException.class);
	}

	@Test
	void webhookIsParsedIgnoringUnknownFields() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-NI-Webhook-Secret", "webhook-secret");

		GatewayWebhookEvent event = this.client.parseWebhook(headers, """
				{"outletId": "outlet-1", "eventId": "evt-1", "eventName": "PURCHASED", "brandNewField": 1,
				 "order": {"action": "PURCHASE", "reference": "order-ref-1",
				           "_embedded": {"payment": [{"state": "PURCHASED"}]}}}""");

		assertThat(event).isEqualTo(new GatewayWebhookEvent("evt-1", "PURCHASED", "order-ref-1", null));
	}

	private NiGatewayClient client(String baseUrl) {
		NiProperties properties = new NiProperties(baseUrl, "test-api-key", "outlet-1", "PURCHASE",
				Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(60),
				new NiProperties.Retry(2, Duration.ofMillis(20), 2.0),
				new NiProperties.Webhook("X-NI-Webhook-Secret", "webhook-secret"));
		return new NiGatewayConfiguration().niGatewayClient(RestClient.builder(), properties, this.json,
				Clock.systemUTC());
	}

	private static GatewayPaymentRequest paymentRequest() {
		return new GatewayPaymentRequest("pay-12345678", new Money("AED", 100),
				new CardDetails("2303779999000408", "2029-04", "123", "John Brown"));
	}

	private static GatewayLinks links() {
		return new GatewayLinks(null, ni.baseUrl() + AUTH_PATH, null, null);
	}

	private static BrowserInfo browserInfo() {
		return new BrowserInfo("application/json", false, "en", "0", "Mozilla/5.0", "24", "1055", "1680", true,
				"203.0.113.7", "05");
	}

	private static String fixture(String name) {
		try (InputStream in = NiGatewayClientTest.class.getResourceAsStream("/wiremock/ni/__files/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{BASE}}", ni.baseUrl());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
