package com.fazil.payments.repository;

import java.util.Optional;
import java.util.function.Consumer;

import com.fazil.payments.domain.model.Payment;

/**
 * Write operations with the two guarantees the payment flow relies on: a payment id can only be created once,
 * and concurrent updates (API call, 3DS notification, webhook, reconciliation) never overwrite each other.
 */
public interface PaymentRepositoryCustom {

	/**
	 * Inserts the payment unless one with the same id already exists.
	 * @return empty if inserted, otherwise the existing payment
	 */
	Optional<Payment> insertIfAbsent(Payment payment);

	/**
	 * Re-reads the payment, applies {@code mutation} and saves with optimistic locking, retrying on conflict.
	 * {@code mutation} must be side-effect free apart from changing the payment, as it may run more than once.
	 * @throws IllegalStateException if the payment does not exist
	 */
	Payment update(String id, Consumer<Payment> mutation);

}
