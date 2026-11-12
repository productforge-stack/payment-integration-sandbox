# Working rules for Claude in this repository

This is a payment integration sample. A bug here means a customer is charged twice or not at all, so these rules come before speed.

## Definition of done
- Tests first. Write the failing test, run it, see it fail, then write the code. No feature is done without passing tests.
- `./gradlew :payments-core:check` passes, including the 90% line coverage gate. Don't lower the gate to make a build pass.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` passes.
- UI changes come with a Compose UI test in `app/src/androidTest`, and CI's emulator job passes.
- One change per commit, with a message that says what changed and why.

## Payment rules the code must keep
- Money is `Long` minor units (`Money`). Never `Double` or `Float`. Reject fractions of a cent; don't round.
- Every submit carries an idempotency key. One payment attempt uses one key for its whole life, including recovery.
- A timeout or 5xx means **the outcome is unknown**. Look the key up before doing anything else. Only resend, with the same key, after the gateway confirms it never received the request.
- A connection that fails before sending (`NetworkUnavailable`) charged nothing and is safe to retry.
- If the outcome still can't be confirmed, return `PaymentResult.Unknown` and stop. Never "retry with a new key" to get unstuck.
- OkHttp's silent `retryOnConnectionFailure` stays off in `RestPaymentGateway`. Retries are `PaymentService` decisions only.
- Log steps, never card data or API keys.

## Layout
- `payments-core/`: plain Kotlin/JVM library, the integration logic. Callable from Java (see `JavaInteropTest.java`).
- `payments-core/.../sim/`: `GatewaySimulator`, an OkHttp interceptor that behaves like the gateway's REST API, with failure scenarios.
- `app/`: Jetpack Compose sandbox. `PaymentController` holds state and is unit-tested on the JVM; `PaymentScreen` is UI only.
- `docs/`: the integration guide and troubleshooting table. Update them in the same commit as behaviour changes.

## Before reviewing or changing payment code
Use the `payment-integration-review` skill in `.claude/skills/` and work through its checklist.
