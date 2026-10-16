package com.fazil.payments.api.error;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fazil.payments.domain.exception.IdempotencyConflictException;
import com.fazil.payments.domain.exception.PaymentNotFoundException;
import com.fazil.payments.domain.exception.PaymentStateException;
import com.fazil.payments.gateway.exception.GatewayException;
import com.fazil.payments.gateway.exception.GatewayOutcomeUnknownException;
import com.fazil.payments.gateway.exception.InvalidWebhookException;
import com.fazil.payments.gateway.exception.WebhookAuthenticationException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem responses. Spring MVC's own exceptions (missing header, unreadable body,
 * unsupported media type, ...) are handled by {@link ResponseEntityExceptionHandler}, whose messages never echo
 * request content. Validation errors report field names and constraint messages only, never the rejected
 * values, because those can be card data.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final String VALIDATION_FAILED = "Request validation failed";

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> fieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(GlobalExceptionHandler::fieldErrors)
			.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	/**
	 * A {@code @Valid} body validated as part of method validation reports nested field errors (e.g.
	 * {@code card.pan}); other parameters report under their own name.
	 */
	private static Stream<Map<String, String>> fieldErrors(ParameterValidationResult result) {
		if (result instanceof ParameterErrors errors) {
			return errors.getFieldErrors()
				.stream()
				.map(error -> fieldError(error.getField(), error.getDefaultMessage()));
		}
		String parameterName = result.getMethodParameter().getParameterName();
		return result.getResolvableErrors().stream().map(error -> fieldError(parameterName, error.getDefaultMessage()));
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler({ IdempotencyConflictException.class, PaymentStateException.class })
	ProblemDetail handleConflict(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(WebhookAuthenticationException.class)
	ProblemDetail handleWebhookAuthentication(WebhookAuthenticationException ex) {
		log.warn("Rejected webhook: {}", ex.getMessage());
		return ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler(InvalidWebhookException.class)
	ProblemDetail handleInvalidWebhook(InvalidWebhookException ex) {
		log.warn("Rejected webhook: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(GatewayOutcomeUnknownException.class)
	ProblemDetail handleGatewayOutcomeUnknown(GatewayOutcomeUnknownException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
				"Payment gateway outcome unknown; do not retry, check the payment status later");
	}

	@ExceptionHandler(GatewayException.class)
	ProblemDetail handleGatewayFailure(GatewayException ex) {
		log.warn("Gateway failure: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Payment gateway unavailable");
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled error", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
	}

	private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, VALIDATION_FAILED);
		problem.setProperty("errors", errors);
		return problem;
	}

	private static Map<String, String> fieldError(String field, String message) {
		return Map.of("field", String.valueOf(field), "message", String.valueOf(message));
	}

}
