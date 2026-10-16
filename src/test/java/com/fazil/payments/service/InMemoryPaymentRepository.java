package com.fazil.payments.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fazil.payments.domain.model.Payment;
import com.fazil.payments.repository.PaymentRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Single-threaded stand-in for the Elasticsearch-backed repository, covering the methods the services use. */
final class InMemoryPaymentRepository {

	private InMemoryPaymentRepository() {
	}

	@SuppressWarnings("unchecked")
	static PaymentRepository create() {
		Map<String, Payment> payments = new ConcurrentHashMap<>();
		PaymentRepository repository = mock(PaymentRepository.class);
		when(repository.findById(anyString()))
			.thenAnswer(invocation -> Optional.ofNullable(payments.get(invocation.<String>getArgument(0))));
		when(repository.insertIfAbsent(any())).thenAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			return Optional.ofNullable(payments.putIfAbsent(payment.getId(), payment));
		});
		when(repository.update(anyString(), any())).thenAnswer(invocation -> {
			Payment payment = payments.get(invocation.<String>getArgument(0));
			invocation.<Consumer<Payment>>getArgument(1).accept(payment);
			return payment;
		});
		return repository;
	}

}
