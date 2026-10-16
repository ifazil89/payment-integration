# N-Genius Online: One-Stage Direct API Integration Spec

Condensed from the official N-Genius docs (docs.ngenius-payments.com). Verify against the live docs before go-live; field names and URLs can change.

Sandbox base URL: `https://api-gateway.sandbox.ngenius-payments.com`

## Read first

- **PCI scope:** this flow sends raw PAN, expiry and CVV through our server. That brings heavy PCI DSS obligations. If that is not intended, use the Hosted Session / Web SDK instead.
- **Docs inconsistency:** the "Direct API integration sequence diagram" page describes an older shape (`PUT .../card`, states `STARTED`/`AWAITING_3DS`). The one-stage page uses `POST .../payment/card` and `AWAIT_3DS`. This spec follows the one-stage page.
- **Always follow `_links`:** use hrefs returned in responses instead of building URLs by hand. The EMV 3DS doc shows a production host in one challenge-response URL; the `_links` href is authoritative.
- **Treat `state` as an open enum.** Docs mix `PURCHASE` and `PURCHASED`, and `AUTHORIZED`/`AUTHORISED`. Normalise, and log unknown values instead of failing.

## Flow overview

1. Get access token (cache, 300 s lifetime).
2. POST order + card in one call (`/payment/card`).
3. If response `state` is `AWAIT_3DS`: run EMV 3DS (authentication call, optional challenge, challenge-response).
4. Confirm final outcome via GET order.
5. Webhook updates status asynchronously.
6. Refund later if needed.

## Step 1: Access token

`POST {base}/identity/auth/access-token`

Headers:

| Header | Value |
|---|---|
| Authorization | `Basic <service_account_api_key>` |
| Content-Type | `application/vnd.ni-identity.v1+json` |

Response:

```json
{
  "access_token": "eyJ...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJ...",
  "token_type": "bearer"
}
```

Implementation notes: cache in memory, refresh before expiry (for example at 240 s), make refresh thread-safe, never log the token or API key.

## Step 2: Create order and submit card

`POST {base}/transactions/outlets/{outletRef}/payment/card`

Headers:

| Header | Value |
|---|---|
| Authorization | `Bearer <access_token>` |
| Content-Type | `application/vnd.ni-payment.v2+json` |
| Accept | `application/vnd.ni-payment.v2+json` |

Request:

```json
{
  "order": {
    "action": "PURCHASE",
    "amount": { "currencyCode": "AED", "value": 100 }
  },
  "payment": {
    "pan": "4111111111111111",
    "expiry": "2029-04",
    "cvv": "123",
    "cardholderName": "John Brown"
  }
}
```

| Field | Notes |
|---|---|
| order.action | `AUTH`, `SALE` or `PURCHASE`. `PURCHASE` auto-captures after successful authorization. `AUTH` needs a later capture call. NI recommends `AUTH` unless goods or services are delivered immediately. |
| order.amount.value | Minor units. `100` = 1.00 AED. |
| payment.expiry | `YYYY-MM` |

More optional order attributes exist. See the "List of order input attributes" page. Check it for a way to attach our own merchant reference to the order (needed for timeout reconciliation).

Response when 3DS is required (trimmed):

```json
{
  "_id": "urn:payment:<paymentRef>",
  "reference": "<paymentRef>",
  "orderReference": "<orderRef>",
  "outletId": "<outletRef>",
  "state": "AWAIT_3DS",
  "paymentMethod": { "name": "VISA", "pan": "411111******1111", "expiry": "2029-01", "cardholderName": "..." },
  "amount": { "currencyCode": "AED", "value": 100 },
  "3ds2": {
    "messageVersion": "2.1.0",
    "threeDSMethodURL": "https://...",
    "threeDSServerTransID": "...",
    "directoryServerID": "M000000004"
  },
  "_links": {
    "self": { "href": ".../payments/{paymentRef}" },
    "cnp:3ds2-authentication": { "href": ".../payments/{paymentRef}/card/3ds2/authentications" },
    "cnp:3ds2-challenge-response": { "href": ".../payments/{paymentRef}/card/3ds2/challenge-response" },
    "cnp:3ds": { "href": ".../payments/{paymentRef}/card/3ds" }
  }
}
```

If the card is not enrolled for EMV 3DS (or it is not enabled on the account), the `3ds2` block and the extra links may be absent. Handle the final state directly in that case.

## Step 3: EMV 3DS (Direct API only)

Prerequisites: EMV 3DS enabled on the account; a URL on our server that receives the ACS result (`notificationURL`); the frontend captures shopper browser info.

### 3a. Send device info

`POST` to `_links["cnp:3ds2-authentication"].href`

Headers: `Authorization: Bearer <token>`, `Content-Type: application/vnd.ni-payment.v2+json`

```json
{
  "deviceChannel": "BRW",
  "threeDSCompInd": "Y",
  "notificationURL": "https://yourhost/3ds/notify",
  "browserInfo": {
    "browserAcceptHeader": "application/json",
    "browserJavaEnabled": true,
    "browserLanguage": "en",
    "browserTZ": "0",
    "browserUserAgent": "Mozilla/5.0 ...",
    "browserColorDepth": "30",
    "browserScreenHeight": "1055",
    "browserScreenWidth": "1680",
    "browserJavascriptEnabled": true,
    "browserIP": "192.168.1.1",
    "challengeWindowSize": "05"
  }
}
```

- `deviceChannel`: `BRW` for web (default). `APP` is documented as not yet available.
- `threeDSCompInd`: always `Y`.

Response `3ds2` block (challenge case):

```json
"3ds2": {
  "eci": "02",
  "transStatus": "C",
  "messageVersion": "2.1.0",
  "acsReferenceNumber": "00001ACS00001",
  "threeDSMethodURL": "https://...",
  "threeDSServerTransID": "...",
  "acsURL": "https://.../acs?challengeNotificationUrl=...",
  "acsTransID": "...",
  "directoryServerID": "M000000004",
  "base64EncodedCReq": "eyJ..."
}
```

Decision:

- `transStatus != "C"`: frictionless. No challenge. Check the order `state` for the outcome (successful states include `PURCHASED`, `AUTHORISED`, `CAPTURED`; otherwise failed or declined).
- `transStatus == "C"` (and order state still `AWAIT_3DS`): challenge required, go to 3b.

### 3b. Invoke the challenge (shopper browser)

Build this JSON, Base64-encode it, and form-POST it as field `creq` to `3ds2.acsURL` from the shopper's browser (this redirects them to the ACS):

```json
{
  "threeDSServerTransID": "<from 3ds2 block>",
  "acsTransID": "<from 3ds2 block>",
  "challengeWindowSize": "05",
  "messageType": "CReq",
  "messageVersion": "<from 3ds2 block>"
}
```

| challengeWindowSize | Size |
|---|---|
| 01 | 250 x 400 |
| 02 | 390 x 400 |
| 03 | 500 x 600 |
| 04 | 600 x 400 |
| 05 | Full screen |

### 3c. Receive the result

After the challenge, the ACS form-POSTs field `cres` to our `notificationURL`. Do not decode or modify it.

### 3d. Complete the payment

`POST` to `_links["cnp:3ds2-challenge-response"].href`

Headers: `Authorization: Bearer <token>`, `Content-Type: application/vnd.ni-payment.v2+json`

```json
{ "base64EncodedCRes": "<cres value, unchanged>" }
```

The response carries the final order `state`. If the challenge failed, expect a rejection.

The docs also link a recipe, "EMV 3DS2 Authentication with N-Genius (one-stage Payments)". Read it in full before implementing 3DS (it covers details such as the threeDSMethod step).

### 3DS sandbox test cards

Any future expiry and any 3-digit CVV work.

| PAN | Mode | Outcome |
|---|---|---|
| 2303779999000275 | Frictionless (transStatus != C) | Success |
| 2303779999000408 | Challenge (C) | Success |
| 2303779999000291 | Frictionless (N) | Failed, not authenticated |
| 2303779999000317 | Frictionless (U) | Failed, unavailable |
| 2303779999000424 | Challenge (N) | Failed, not authenticated |
| 2303779999000432 | Challenge (U) | Failed, unavailable |

## Step 4: Retrieve order status

`GET {base}/transactions/outlets/{outletRef}/orders/{orderRef}`

Header: `Authorization: Bearer <token>` (no body).

Read `_embedded.payment[0].state` for the payment outcome. The response also contains follow-up links (for example `cnp:refund` once settled, `cnp:3ds2-*` while awaiting 3DS). Never rely on the browser redirect alone.

## Step 5: Webhooks

Configure in the portal: Settings > Integrations > Manage Webhooks > Add. Fields: name, HTTPS URL, optional header key and value. NI reviews and whitelists the URL before it goes live.

Behaviour:

- One POST per event. **No retries** if we are down or slow.
- Respond HTTP 200 or 201 within 15 seconds. Acknowledge first, process after.
- Payloads are **not signed**. Configure a secret header and verify it on every request.
- Multiple events can arrive for one order, in quick succession. Handle idempotently.
- Ignore unknown JSON fields and unknown event types (log and skip, do not error).

Relevant events for the purchase flow: `PURCHASED`, `PURCHASE_DECLINED`, `PURCHASE_FAILED`, `PURCHASE_REVERSED`, `PURCHASE_REVERSAL_FAILED`, plus `GATEWAY_RISK_PRE_AUTH_REJECTED`, `PRE_AUTH_FRAUD_CHECK_REJECTED`, `POST_AUTH_FRAUD_CHECK_REJECTED` where risk modules are enabled. For `AUTH` orders: `AUTHORISED`, `DECLINED`, `AUTHORISATION_FAILED`, `CAPTURED`, `CAPTURE_FAILED`, `FULL_AUTH_REVERSED`, and the refund events (`REFUNDED`, `PARTIALLY_REFUNDED`, `REFUND_FAILED`).

Key payload fields:

| Field | Use |
|---|---|
| eventId | Unique per event. Store after processing, dedupe on it. |
| eventName | Event type. |
| outletId | Outlet that received the payment. |
| order.action | `AUTH`, `SALE` or `PURCHASE`. |
| order.reference | Order reference, match to our record. |
| order.amount | `currencyCode` + `value` (minor units). |
| order._embedded.payment[0].state | Payment state. |
| order._embedded.payment[0].authResponse | `success`, `resultCode`, `authorizationCode`, `rrn`. |

Decide completion from `eventName`, `order.action` and payment `state` together, never from one field.

Sample (PURCHASED, trimmed):

```json
{
  "outletId": "<outletRef>",
  "eventId": "<eventId>",
  "eventName": "PURCHASED",
  "order": {
    "action": "PURCHASE",
    "reference": "<orderRef>",
    "amount": { "currencyCode": "AED", "value": 100 },
    "_embedded": {
      "payment": [
        {
          "state": "PURCHASED",
          "amount": { "currencyCode": "AED", "value": 100 },
          "authResponse": { "authorizationCode": "AB0012", "success": true, "resultCode": "00" }
        }
      ]
    }
  }
}
```

Testing: no webhook simulator in the portal. Trigger real sandbox transactions and inspect with a tool like webhook.site.

## Step 6: Refund

`POST` to `response._embedded.payment[0]._links["cnp:refund"].href` (taken from Retrieve Order Status). Alternative: capture `_links.self.href` + `/refund`.

Headers: `Authorization: Bearer <token>`, `Content-Type` and `Accept: application/vnd.ni-payment.v2+json`

```json
{ "amount": { "currencyCode": "AED", "value": 100 } }
```

- Allowed only when the payment is `PURCHASED` or `CAPTURED`.
- Amount in minor units; partial refunds are supported (not for multi-currency-pricing payments).
- Success response shows `state: REFUNDED` and a `_embedded["cnp:refund"]` entry with `state: SUCCESS`.

## Suggested internal state machine

`CREATED` -> `AWAIT_3DS` -> `PURCHASED` | `AUTHORISED` | `DECLINED` | `FAILED`
`PURCHASED`/`CAPTURED` -> `REFUNDED` | `PARTIALLY_REFUNDED`
Any state -> `UNKNOWN` on timeout (resolve via GET order). Reject illegal transitions and log them.

## Reliability rules

- Never blind-retry the payment POST. A timeout leaves the outcome unknown: mark `UNKNOWN`, then reconcile with GET order.
- Create the local payment record (with a unique merchant reference) before calling NI.
- GET and the token call are safe to retry with backoff. Apply timeouts and a circuit breaker to every NI call.
- Run a scheduled reconciliation job for payments stuck in `AWAIT_3DS`, `UNKNOWN` or pending beyond N minutes. Webhooks are not retried, so this is the safety net.
- Do not treat the shopper's browser return (the 3DS notification redirect) as proof of payment. Confirm via GET order or webhook.

## Security and logging rules

- PAN, CVV and expiry: never logged, never persisted, never in exceptions, metrics or traces. Mask in DTO `toString()`.
- API key and tokens come from config or a vault, never source control, never logs.
- Webhook endpoint: verify secret header, HTTPS only, rate limit, constant-time comparison.
- Correlation ID on every request (MDC); log order reference and state transitions only.

## Suggested CLAUDE.md section

```markdown
## NI N-Genius (one-stage Direct API)
- Spec: @docs/ngenius/ngenius-one-stage-spec.md (follow exactly; follow _links hrefs, do not hand-build URLs)
- All NI calls live in NiGatewayClient behind the gateway interface. Token cached, refreshed before 300s.
- Amounts are minor units (long) + currency code. Never double.
- PAN/CVV/expiry: never logged, never persisted, never in exceptions or metrics. Mask in all DTO toString().
- Treat payment `state` as an open enum; unknown values are logged, not fatal.
- Never blind-retry the payment POST. On timeout mark payment UNKNOWN and reconcile via GET order.
- Webhook: verify secret header, dedupe by eventId, return 200 fast, process async, ignore unknown fields/events.
- 3DS: follow the one-stage EMV 3DS2 recipe; pass cres to NI unchanged.
- A task is not done until `./mvnw verify` passes (WireMock tests built from the example JSON above).
```

## Suggested Claude Code build order

1. Plan mode: propose design, schema, endpoints and state machine from this spec. No code.
2. Flyway migration, entity, repository.
3. `NiTokenProvider` + `NiGatewayClient` (RestClient, timeouts, typed errors), WireMock tests.
4. Pay endpoint (calls step 2) and 3DS authentication (step 3a).
5. 3DS challenge and notification endpoint (3b to 3d).
6. Status fetch and state mapping (step 4).
7. Webhook endpoint (step 5).
8. Refund (step 6).
9. Reconciliation job, metrics, health checks.
10. Sandbox end-to-end run with the frictionless and challenge test cards.

## Sources

- https://docs.ngenius-payments.com/reference/one-stage-payments-1
- https://docs.ngenius-payments.com/reference/request-an-access-token-direct
- https://docs.ngenius-payments.com/reference/emv-3ds-3ds-2x-integration-2
- https://docs.ngenius-payments.com/reference/retrieve-order-status
- https://docs.ngenius-payments.com/reference/consuming-web-hooks
- https://docs.ngenius-payments.com/reference/refund-a-capture-1
- https://docs.ngenius-payments.com/reference/direct-api-integration-sequence-diagram
- Index of all pages (Markdown): https://docs.ngenius-payments.com/llms.txt
