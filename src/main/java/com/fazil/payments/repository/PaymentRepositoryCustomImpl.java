package com.fazil.payments.repository;

import java.util.Optional;
import java.util.function.Consumer;

import com.fazil.payments.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.data.elasticsearch.VersionConflictException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;

/** Spring Data fragment implementation, picked up by its {@code Impl} suffix. */
@RequiredArgsConstructor
@Slf4j
class PaymentRepositoryCustomImpl implements PaymentRepositoryCustom {

	private static final int MAX_UPDATE_ATTEMPTS = 5;

	private final ElasticsearchOperations operations;

	@Override
	public Optional<Payment> insertIfAbsent(Payment payment) {
		IndexQuery query = new IndexQueryBuilder().withId(payment.getId())
			.withObject(payment)
			.withOpType(IndexQuery.OpType.CREATE)
			.build();
		try {
			this.operations.index(query, this.operations.getIndexCoordinatesFor(Payment.class));
			return Optional.empty();
		}
		catch (DataAccessException ex) {
			if (!isConflict(ex)) {
				throw ex;
			}
			return Optional.of(load(payment.getId()));
		}
	}

	@Override
	public Payment update(String id, Consumer<Payment> mutation) {
		for (int attempt = 1;; attempt++) {
			Payment payment = load(id);
			mutation.accept(payment);
			try {
				return this.operations.save(payment);
			}
			catch (OptimisticLockingFailureException ex) {
				if (attempt >= MAX_UPDATE_ATTEMPTS) {
					throw ex;
				}
				log.debug("Concurrent update on payment {}, retrying (attempt {})", id, attempt);
			}
		}
	}

	private Payment load(String id) {
		Payment payment = this.operations.get(id, Payment.class);
		if (payment == null) {
			throw new IllegalStateException("Payment " + id + " not found");
		}
		return payment;
	}

	private static boolean isConflict(DataAccessException ex) {
		// A CREATE on an existing id is reported as a 409 that Spring Data does not always categorise.
		return ex instanceof VersionConflictException || ex instanceof OptimisticLockingFailureException
				|| (ex instanceof UncategorizedElasticsearchException uncategorized
						&& Integer.valueOf(409).equals(uncategorized.getStatusCode()));
	}

}
