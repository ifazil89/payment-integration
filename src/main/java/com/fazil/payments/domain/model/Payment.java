package com.fazil.payments.domain.model;

import java.time.Duration;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.core.query.SeqNoPrimaryTerm;

/**
 * A payment as we know it. Holds no card data beyond the masked PAN returned by the gateway. Concurrent
 * updates are guarded by Elasticsearch optimistic locking ({@link #seqNoPrimaryTerm}).
 */
@Document(indexName = "payments")
@Getter
@NoArgsConstructor
@ToString(exclude = { "challengeCreq", "seqNoPrimaryTerm" })
@Slf4j
public class Payment {

	/** Our merchant reference; equals the client's idempotency key. */
	@Id
	private String id;

	@Field(type = FieldType.Keyword)
	private PaymentStatus status;

	@Field(type = FieldType.Long)
	private long amountMinor;

	@Field(type = FieldType.Keyword)
	private String currency;

	@Field(type = FieldType.Long)
	private long refundedMinor;

	@Field(type = FieldType.Keyword)
	private String gatewayOrderReference;

	@Field(type = FieldType.Keyword)
	private String gatewayPaymentReference;

	/** Gateway state as last seen, unnormalised. */
	@Field(type = FieldType.Keyword)
	private String gatewayState;

	@Field(type = FieldType.Keyword)
	private String failureCode;

	@Field(type = FieldType.Keyword)
	private String maskedPan;

	@Field(type = FieldType.Keyword)
	private String cardBrand;

	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String selfLink;

	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String threeDsAuthenticationLink;

	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String threeDsChallengeResponseLink;

	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String refundLink;

	/** Kept so an idempotent replay can hand the same challenge back to the client. */
	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String challengeAcsUrl;

	@Field(type = FieldType.Keyword, index = false, docValues = false)
	private String challengeCreq;

	@Field(type = FieldType.Date, format = DateFormat.epoch_millis)
	private Instant createdAt;

	@Field(type = FieldType.Date, format = DateFormat.epoch_millis)
	private Instant updatedAt;

	/** When the reconciliation job should next ask the gateway about this payment; null when nothing to do. */
	@Field(type = FieldType.Date, format = DateFormat.epoch_millis)
	private Instant nextCheckAt;

	private SeqNoPrimaryTerm seqNoPrimaryTerm;

	public static Payment create(String id, Money amount, Instant now, Duration recheckAfter) {
		Payment payment = new Payment();
		payment.id = id;
		payment.status = PaymentStatus.CREATED;
		payment.amountMinor = amount.minorUnits();
		payment.currency = amount.currency();
		payment.createdAt = now;
		payment.updatedAt = now;
		payment.nextCheckAt = now.plus(recheckAfter);
		return payment;
	}

	public Money getAmount() {
		return new Money(this.currency, this.amountMinor);
	}

	public long getRefundableMinor() {
		return this.amountMinor - this.refundedMinor;
	}

	public GatewayLinks getLinks() {
		return new GatewayLinks(this.selfLink, this.threeDsAuthenticationLink, this.threeDsChallengeResponseLink,
				this.refundLink);
	}

	public ThreeDsChallenge getChallenge() {
		return (this.status == PaymentStatus.AWAIT_3DS && this.challengeAcsUrl != null)
				? new ThreeDsChallenge(this.challengeAcsUrl, this.challengeCreq) : null;
	}

	/**
	 * Moves to {@code target} if the state machine allows it. Illegal transitions (typically late or
	 * out-of-order gateway updates) are logged and ignored.
	 * @return whether the status changed
	 */
	public boolean transitionTo(PaymentStatus target) {
		if (target == this.status) {
			return false;
		}
		if (!this.status.canTransitionTo(target)) {
			log.warn("Rejected illegal transition for payment {}: {} -> {}", this.id, this.status, target);
			return false;
		}
		log.info("Payment {} {} -> {}", this.id, this.status, target);
		this.status = target;
		return true;
	}

	/** Records everything the gateway told us and moves the state machine accordingly. */
	public void apply(GatewayPaymentResult result, Instant now, Duration recheckAfter) {
		if (result.orderReference() != null) {
			this.gatewayOrderReference = result.orderReference();
		}
		if (result.paymentReference() != null) {
			this.gatewayPaymentReference = result.paymentReference();
		}
		if (result.maskedPan() != null) {
			this.maskedPan = result.maskedPan();
		}
		if (result.cardBrand() != null) {
			this.cardBrand = result.cardBrand();
		}
		this.gatewayState = result.rawState();
		GatewayLinks links = getLinks().merge(result.links());
		this.selfLink = links.self();
		this.threeDsAuthenticationLink = links.threeDsAuthentication();
		this.threeDsChallengeResponseLink = links.threeDsChallengeResponse();
		this.refundLink = links.refund();
		if (result.status() != null) {
			transitionTo(result.status());
		}
		if (this.status == PaymentStatus.DECLINED || this.status == PaymentStatus.FAILED) {
			this.failureCode = (result.resultCode() != null) ? result.resultCode() : this.failureCode;
		}
		if (result.challenge() != null) {
			this.challengeAcsUrl = result.challenge().acsUrl();
			this.challengeCreq = result.challenge().creq();
		}
		touch(now, recheckAfter);
	}

	public void fail(String failureCode, Instant now) {
		this.failureCode = failureCode;
		transitionTo(PaymentStatus.FAILED);
		touch(now, null);
	}

	public void markUnknown(Instant now, Duration recheckAfter) {
		transitionTo(PaymentStatus.UNKNOWN);
		touch(now, recheckAfter);
	}

	public void addRefund(long minorUnits) {
		this.refundedMinor += minorUnits;
	}

	/** Schedules a gateway check even if the status is not pending (e.g. a refund with unknown outcome). */
	public void scheduleCheck(Instant at) {
		this.nextCheckAt = at;
	}

	public void stopChecking() {
		this.nextCheckAt = null;
	}

	private void touch(Instant now, Duration recheckAfter) {
		this.updatedAt = now;
		if (this.status != PaymentStatus.AWAIT_3DS) {
			this.challengeAcsUrl = null;
			this.challengeCreq = null;
		}
		this.nextCheckAt = (this.status.isPending() && recheckAfter != null) ? now.plus(recheckAfter) : null;
	}

}
