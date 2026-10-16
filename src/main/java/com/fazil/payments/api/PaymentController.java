package com.fazil.payments.api;

import java.net.URI;

import com.fazil.payments.api.dto.request.CreatePaymentRequest;
import com.fazil.payments.api.dto.request.RefundRequest;
import com.fazil.payments.api.dto.response.PaymentResponse;
import com.fazil.payments.api.mapper.PaymentApiMapper;
import com.fazil.payments.api.validation.PaymentId;
import com.fazil.payments.service.CreatePaymentResult;
import com.fazil.payments.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Card payments with EMV 3DS")
public class PaymentController {

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final PaymentService paymentService;

	private final PaymentApiMapper paymentApiMapper;

	@PostMapping
	@Operation(summary = "Create and submit a card payment",
			description = "The Idempotency-Key becomes the payment id: repeating the request returns the existing "
					+ "payment (200) instead of charging again (201).")
	public ResponseEntity<PaymentResponse> createPayment(
			@RequestHeader(IDEMPOTENCY_KEY_HEADER) @PaymentId String idempotencyKey,
			@Valid @RequestBody CreatePaymentRequest request, HttpServletRequest servletRequest) {
		CreatePaymentResult result = this.paymentService
			.createPayment(this.paymentApiMapper.toCommand(idempotencyKey, request, servletRequest.getRemoteAddr()));
		PaymentResponse body = this.paymentApiMapper.toResponse(result.payment());
		if (result.replayed()) {
			return ResponseEntity.ok(body);
		}
		URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
			.path("/{id}")
			.buildAndExpand(body.id())
			.toUri();
		return ResponseEntity.created(location).body(body);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a payment")
	public PaymentResponse getPayment(@PathVariable @PaymentId String id,
			@Parameter(description = "Re-read the state from the gateway before answering") @RequestParam(
					defaultValue = "false") boolean refresh) {
		return this.paymentApiMapper
			.toResponse(refresh ? this.paymentService.reconcile(id) : this.paymentService.getPayment(id));
	}

	@PostMapping("/{id}/refunds")
	@Operation(summary = "Refund all or part of a captured payment")
	public PaymentResponse refundPayment(@PathVariable @PaymentId String id,
			@Valid @RequestBody RefundRequest request) {
		return this.paymentApiMapper
			.toResponse(this.paymentService.refund(id, this.paymentApiMapper.toMoney(request.amount())));
	}

}
