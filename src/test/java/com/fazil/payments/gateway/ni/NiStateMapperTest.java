package com.fazil.payments.gateway.ni;

import com.fazil.payments.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NiStateMapperTest {

	@Test
	void normalisesSpellingVariants() {
		assertThat(NiStateMapper.toStatus("PURCHASE")).contains(PaymentStatus.PURCHASED);
		assertThat(NiStateMapper.toStatus("purchased")).contains(PaymentStatus.PURCHASED);
		assertThat(NiStateMapper.toStatus("AUTHORIZED")).contains(PaymentStatus.AUTHORISED);
		assertThat(NiStateMapper.toStatus("AWAITING_3DS")).contains(PaymentStatus.AWAIT_3DS);
	}

	@Test
	void unknownStatesAreNotFatal() {
		assertThat(NiStateMapper.toStatus("SOMETHING_NEW")).isEmpty();
		assertThat(NiStateMapper.toStatus(null)).isEmpty();
		assertThat(NiStateMapper.toStatus(" ")).isEmpty();
	}

}
