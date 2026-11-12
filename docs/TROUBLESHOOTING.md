# Troubleshooting

Written the way I'd want a support ticket answered: what the customer sees, the likely cause, how to confirm it, and the fix.

| Symptom | Likely cause | How to confirm | Fix |
|---|---|---|---|
| Customer charged twice for one purchase | App resent after a timeout with a new idempotency key, or the HTTP client retried silently | Two transactions with different keys seconds apart; or same key logged twice by the client but only one by the gateway | Use one key per payment attempt; look up before resending; turn off client auto-retry |
| Clerk told "payment failed" but the customer was charged | A timeout or 5xx was treated as a failure | Gateway shows an approved transaction for the key the app gave up on | Treat timeout and 5xx as unknown; look up by key before reporting failure |
| Every request returns 400 | `Idempotency-Key` header missing, often stripped by a proxy | Capture the request with a proxy or Postman and check headers | Send the header on every POST; allow it through proxies |
| Every request returns 401 | Wrong environment's API key (sandbox key against production, or the reverse) | Compare the key prefix and base URL in the config | Load key and base URL from the same environment config |
| Refund returns 422 | Refund larger than the original, or the original was declined or never approved | Look up the original transaction ID | Refund at most the original amount, against an approved sale |
| Amounts off by a cent | Floating-point maths on money | Search for `Double`/`Float` on amount fields | Use integer minor units end to end |
| Payments hang on the POS | Network call on the Android main thread, or no read timeout set | ANR traces; StrictMode; OkHttp timeouts | Call from `Dispatchers.IO`; set connect and read timeouts |
| "Needs reconciliation" shown | Gateway unreachable for the whole recovery window | Look up the key once the gateway is back | Resolve from the gateway's answer; don't re-run the card until then |
