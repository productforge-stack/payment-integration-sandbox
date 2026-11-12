---
name: payment-integration-review
description: Review payment integration code or a customer's integration plan for double-charge, lost-payment and data-handling risks. Use when reviewing or changing code that submits, refunds, voids or looks up transactions, or when helping a customer validate their integration.
---

# Payment integration review

Work through every section. For each finding, give the file and line (or the step in the customer's flow), what can go wrong for the cardholder or merchant, and the fix. End with a short verdict: ready, ready with fixes, or not ready.

## 1. Amounts
- Amounts are integers in minor units end to end: UI parsing, storage, JSON, logs. Flag any `Double`, `Float` or JavaScript `number` arithmetic on money.
- Input with more than two decimal places is rejected, not rounded.
- Refund and partial-void amounts are checked against the original transaction.

## 2. Idempotency
- Every submit sends an idempotency key, and one payment attempt keeps the same key through every retry and recovery step.
- A new key is created only for a genuinely new payment, never to get past an error.
- Keys are generated on the device or POS before the first send, not by the server.

## 3. Unknown outcomes
Classify every failure path:

| Failure | Outcome | Correct handling |
|---|---|---|
| Connection refused, DNS failure, no network | Nothing sent | Safe to retry |
| Read timeout, connection dropped after send | Unknown | Look up by key before anything else |
| HTTP 5xx, or a 2xx body that can't be parsed | Unknown | Look up by key before anything else |
| HTTP 4xx | Rejected, nothing charged | Fix the request; don't retry as is |

- Flag any code that resends after a timeout without a lookup first.
- Flag HTTP client settings that retry silently (for example OkHttp `retryOnConnectionFailure`).
- When recovery can't confirm the outcome, the flow must stop and surface "needs reconciliation" with the key. It must not tell the clerk to run the card again.

## 4. Refunds and voids
- They reference the original transaction ID, use their own idempotency key, and get the same unknown-outcome handling as sales.
- The UI can't refund or void a declined or unknown transaction.

## 5. Data handling
- No PAN, track data, CVV, or API keys in logs, analytics, crash reports or screenshots.
- API keys are not hard-coded in source; check build config and CI secrets.
- Network traffic is HTTPS only, with certificate validation left on.

## 6. Tests
For each item in section 3, a test should exist that forces that failure and checks two things: the result shown to the user, and **how many times the gateway was asked to charge**. If that count isn't asserted, the double-charge risk isn't tested. In this repo, see `PaymentServiceTest` (scripted gateway) and `GatewaySimulatorTest` (end to end).

## Output format
1. Findings, most severe first, each with location, impact and fix.
2. Missing tests, as a list of test names to add.
3. Verdict.
