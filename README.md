# Payment Integration Sandbox

[![CI](https://github.com/productforge-stack/payment-integration-sandbox/actions/workflows/ci.yml/badge.svg)](https://github.com/productforge-stack/payment-integration-sandbox/actions/workflows/ci.yml)

An Android app and Kotlin library showing how to integrate with a payment gateway's REST API **without double-charging anyone** when the network misbehaves.

Built by [Tim Duffy](https://productforge-stack.github.io) with Claude Code, to the test-first rules in [`CLAUDE.md`](CLAUDE.md). I've spent 16 years testing point-of-sale and payment software and supporting Verifone device integrations in the field; this is the integration logic I'd want every customer to have.

## What it does

Pick how the gateway behaves, run a sale, refund or void, and watch every step the integration takes:

| Scenario | What happens | What the app does |
|---|---|---|
| Approve | Normal approval | Shows the auth code |
| Decline | Issuer declines | Shows the reason; refund and void stay disabled |
| Timeout after charge | Card charged, response lost | Looks the payment up by idempotency key, finds it, **doesn't charge again** |
| Gateway error before charge | HTTP 503, nothing processed | Confirms it wasn't received, resends with the **same key** |
| Device offline | Request never leaves the device | Reports nothing was charged and it's safe to retry |
| Gateway down | Nothing can be confirmed | Stops and asks for reconciliation instead of retrying blindly |

The app uses the real HTTP and JSON stack (OkHttp + kotlinx.serialization). Only the server is simulated, by an OkHttp interceptor that behaves like the gateway, so it runs with no backend and no internet permission.

## How it's tested

| Layer | Where | What it proves |
|---|---|---|
| Unit tests, scripted gateway | `PaymentServiceTest` | Every failure path, asserting **how many times the gateway was asked to charge** |
| HTTP tests, real local server | `RestPaymentGatewayTest` (MockWebServer) | Headers, JSON, status codes, timeouts and dropped connections map to the right outcome |
| End-to-end JVM tests | `GatewaySimulatorTest` | Service + REST client + simulated gateway for every scenario, plus API contract checks |
| Java interop | `JavaInteropTest.java` | The library works from plain Java |
| App logic | `PaymentControllerTest` | Screen state, validation, refund/void rules, on the JVM |
| UI tests on an emulator | `PaymentScreenTest` | Compose UI on an Android 14 Pixel 6 emulator in CI |

CI runs all of it on every push and **fails if line coverage of the payment library drops below 90%**.

## Run it

- **Android Studio:** open the folder, let Gradle sync, run `app` on an emulator or device (Android 8.0+).
- **Command line:**
  ```
  ./gradlew :payments-core:check          # library tests + coverage gate
  ./gradlew :app:testDebugUnitTest        # app unit tests
  ./gradlew :app:connectedDebugAndroidTest  # UI tests, needs an emulator or device
  ```

## Docs
- [Integration guide](docs/INTEGRATION_GUIDE.md): the API, the recovery rule, Kotlin and Java usage, and a go-live validation checklist
- [Troubleshooting](docs/TROUBLESHOOTING.md): symptoms, causes and fixes, written as support answers
- [`payment-integration-review` skill](.claude/skills/payment-integration-review/SKILL.md): a Claude skill that reviews integration code or a customer's plan against the same rules

## Layout
```
payments-core/   Kotlin/JVM library: Money, RestPaymentGateway, PaymentService, simulator
app/             Jetpack Compose sandbox app
docs/            Integration guide and troubleshooting
.claude/skills/  Claude skill for integration reviews
```

This is a sample against a simulated gateway. It isn't affiliated with any payment provider and handles no real card data.
