package com.fazil.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fazil.payments.domain.model.Payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface PaymentRepository extends ElasticsearchRepository<Payment, String>, PaymentRepositoryCustom {

	/** Search-based, so near-real-time: a reference saved within the last second may not be found yet. */
	Optional<Payment> findFirstByGatewayOrderReference(String gatewayOrderReference);

	List<Payment> findByNextCheckAtLessThanEqual(Instant now, Pageable pageable);

}
