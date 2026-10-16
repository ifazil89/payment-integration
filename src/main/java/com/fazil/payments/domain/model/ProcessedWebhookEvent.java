package com.fazil.payments.domain.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/** Marker that a webhook event id has been handled, used for de-duplication. */
@Document(indexName = "processed-webhook-events")
public record ProcessedWebhookEvent(@Id String eventId, @Field(type = FieldType.Keyword) String eventName,
		@Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant processedAt) {

}
