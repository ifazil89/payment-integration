package com.fazil.payments.api.dto.request;

import com.fazil.payments.domain.model.CardDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.LuhnCheck;

/** Raw card input. Validation messages never echo the value, and {@link #toString()} is masked. */
public record CardRequest(
		@NotNull @Pattern(regexp = "\\d{12,19}") @LuhnCheck(message = "is not a valid card number") String pan,
		@NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM") String expiry,
		@NotNull @Pattern(regexp = "\\d{3,4}") String cvv, @NotBlank @Size(max = 100) String cardholderName) {

	@Override
	public String toString() {
		return "CardRequest[pan=" + CardDetails.maskPan(this.pan) + ", expiry=**, cvv=***, cardholderName=**]";
	}

}
