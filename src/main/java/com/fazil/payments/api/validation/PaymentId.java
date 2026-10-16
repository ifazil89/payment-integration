package com.fazil.payments.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A payment id, which is also the client's idempotency key: 8-64 letters, digits or hyphens. */
@NotNull
@Pattern(regexp = "[A-Za-z0-9\\-]{8,64}")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Target({ ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface PaymentId {

	String message() default "must be 8-64 letters, digits or hyphens";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
