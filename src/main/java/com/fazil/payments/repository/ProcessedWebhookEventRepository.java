package com.fazil.payments.repository;

import com.fazil.payments.domain.model.ProcessedWebhookEvent;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProcessedWebhookEventRepository extends ElasticsearchRepository<ProcessedWebhookEvent, String> {

}
