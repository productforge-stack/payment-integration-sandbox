# Integration guide

How an app integrates with the sandbox gateway's REST API, and how to validate the integration before go-live. The same rules apply to any card-present or card-not-present payment API.

## API

All requests use HTTPS, JSON, and `Authorization: Bearer <api key>`.

### Submit a transaction
`POST /v1/transactions` with header `Idempotency-Key: <unique key per payment attempt>`

```json
{ "type": "SALE", "amountMinor": 2500, "currency": "USD" }
```

Refunds and voids add `"originalTransactionId": "TXN-1001"`.

Response `200`:
```json
{ "transactionId": "TXN-1001", "type": "SALE", "status": "APPROVED",
  "amountMinor": 2500, "currency": "USD", "authCode": "A1001" }
```

A decline is a normal `200` with `"status": "DECLINED"` and a `declineReason`. It is not an error.

| Status | Meaning |
|---|---|
| 200 | Processed. Read `status` for approved or declined. Sending the same key again returns this same body. |
| 400 | Missing `Idempotency-Key` |
| 401 | Missing or invalid API key |
| 422 | Refund or void doesn't match an approved original, or exceeds its amount |
| 5xx | Gateway error. **The transaction may or may not have been processed.** |

### Look up by idempotency key
`GET /v1/transactions?idempotencyKey=<key>` returns the transaction (`200`) or `404` if the gateway never received that key.

## The one rule that prevents double charges

When you don't get a clear answer (timeout, dropped connection, 5xx), you don't know whether the card was charged. Don't resend, and don't start over with a new key.

1. Look the transaction up by its idempotency key.
2. Found: that's your answer. Show it. Don't resend.
3. Not found: the gateway never received it. Resend **with the same key**.
4. Lookup also fails: retry the lookup a few times (`maxRecoveryAttempts`, default 3).
5. Still unknown: stop. Show "needs reconciliation" with the key, and don't take payment again until it's resolved.

`PaymentService` implements exactly this. `PaymentServiceTest` asserts the exact sequence of submits and lookups for each failure path, and `GatewaySimulatorTest` checks the card is charged once end to end.

## Using the library

Kotlin:
```kotlin
val gateway = RestPaymentGateway(baseUrl, apiKey, OkHttpClient())
val service = PaymentService(gateway)
when (val result = service.sale(Money.parse("25.00"))) {
    is PaymentResult.Approved -> showReceipt(result.response)
    is PaymentResult.Declined -> showDecline(result.response.declineReason)
    is PaymentResult.Failed -> if (result.safeToRetry) offerRetry() else showError(result.reason)
    is PaymentResult.Unknown -> flagForReconciliation(result.idempotencyKey)
}
```

Java:
```java
PaymentService service = new PaymentService(new RestPaymentGateway(baseUrl, apiKey, new OkHttpClient()));
PaymentResult result = service.sale(Money.parse("25.00"));
if (result instanceof PaymentResult.Approved) { ... }
```

Calls block, so on Android run them off the main thread (`Dispatchers.IO`). Java callers need no coroutine code.

## Validation checklist before go-live

Run each scenario in the sandbox app (or `GatewaySimulatorTest`) and confirm the expected result:

| Scenario | Expected result | Charges |
|---|---|---|
| Approve | Approved, auth code shown | 1 |
| Decline | Declined with reason, Refund/Void stay disabled | 0 approved |
| Timeout after charge | Approved, marked recovered | 1 |
| Gateway error before charge | Approved after resend with the same key | 1 |
| Device offline | Not charged, safe to retry | 0 |
| Gateway down | Needs reconciliation, key shown | 0 or 1, never 2 |
| Refund more than the sale | Rejected (422), nothing refunded | 0 |
| Same idempotency key sent twice | Same response both times | 1 |
