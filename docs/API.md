# API reference

Status: Phase 1 (foundation), Phase 2 (domain + database), Phase 3
(revenue risk engine), Phase 4 (recovery safety / policy engine), Phase 5
(AI recovery agent), Phase 6 (Razorpay integration / payment adapter),
Phase 7 (recovery execution pipeline), Phase 8 (failure-recovery demo),
Phase 9 (production deployment), Phase 10 (audit, compliance & production
hardening), Phase 11 (interactive recovery console), Phase 12 (payment
confirmation, webhook verification & measured revenue recovery), and
Phase 13 (general-purpose transaction dashboard), and the production
readiness phase (authentication/authorization, verified recovery
lifecycle testing, latest-attempt dashboard filtering, observability)
complete — the API described below is live at
https://recoverai-xrky.onrender.com. Endpoints are documented here as
they are implemented; see [README.md](../README.md) for overall phase
progress and [README.md § Live deployment](../README.md) for
the deployment record.

Status update - the production readiness phase added authentication to
every endpoint below except health, login, and the webhook (see
[Authentication & authorization](#authentication--authorization-production-readiness-phase)),
added `GET /api/observability/metrics`, and fixed the
`recoveryAttemptStatus` dashboard filter to match a transaction's latest
attempt rather than any attempt.

Status update - Phase 13 evolved `GET /api/transactions` from a minimal
status-filtered list into a full dashboard API (combinable filters,
search, sort, pagination over every transaction) and added
`GET /api/transactions/{id}/detail` — see below. No existing endpoint's
URL or meaning changed; `GET /api/transactions/{id}` is unchanged.

Status update - Phase 12 added two endpoints: `POST /api/webhooks/razorpay`
(inbound payment confirmation) and `GET /api/recovery/metrics` (portfolio
aggregates) — see [Payment confirmation](#post-apiwebhooksrazorpay) and
[Recovery metrics](#get-apirecoverymetrics) below. It also added five
fields to every response that already carried `amountRecovered`
(`RecoveryExecutionResponse`, and the Phase 8 demo scenario response):
`paymentConfirmationStatus`, `confirmedAmount`, `confirmedCurrency`,
`providerPaymentId`, `confirmedAt` — see
[Execution status vs. payment confirmation](#execution-status-vs-payment-confirmation).

Phase 6 deliberately added **no new endpoint** - `PaymentGateway` (mock by
default, real Razorpay Payment Links when explicitly enabled) was
infrastructure for Phase 7 to call. See
[README.md § Razorpay Integration / Payment Adapter](../README.md).

Status update - Phase 10 (audit, compliance & production hardening)
complete, no new endpoints added. Two cross-cutting behaviors apply to
every endpoint below as of this phase:

- **Errors**: a known failure (not found, validation, malformed input)
  returns `{"error": "<message>"}` with an appropriate 4xx status. Any
  genuinely unexpected server error returns a generic
  `{"error": "An unexpected error occurred. Please try again shortly."}`
  with `500` - never a stack trace, exception class, SQL, or internal
  path (verified live). See [README.md § Error handling](../README.md).
- **Rate limiting**: `POST /api/recovery-agent/evaluate*`,
  `POST /api/revenue-risk/analyze-all`, and
  `POST /api/recovery/{id}/execute` return `429 Too Many Requests`
  (`{"error": "Too many requests. Please slow down and try again shortly."}`,
  with a `Retry-After` header) if a single client exceeds the configured
  window (default 20 requests/60s). See [README.md § Rate limiting](../README.md).

## Authentication & authorization (production readiness phase)

Every endpoint below requires a valid bearer token **except** `GET /api/health`,
`POST /api/auth/login`, `POST /api/webhooks/razorpay` (independently
signature-gated - see [Payment confirmation](#payment-confirmation-phase-12)),
and `/actuator/health`. See
[README.md § Authentication & authorization](../README.md) and
[docs/ARCHITECTURE.md § Authentication & Authorization](ARCHITECTURE.md)
for the design and its documented limitations.

#### `POST /api/auth/login`

```json
{ "username": "merchant.admin", "password": "..." }
```

**Response `200 OK`**

```json
{ "token": "eyJhbGciOi...", "tokenType": "Bearer", "role": "MERCHANT_ADMIN", "expiresInSeconds": 28800 }
```

**Response `401 Unauthorized`** for any wrong/unknown username or password —
the same generic `{"error": "Invalid username or password."}` for both, so a
response can never be used to enumerate valid usernames.

Send the token on every subsequent request: `Authorization: Bearer <token>`.

**Roles**

| Role | Can do |
|---|---|
| `OPERATOR` | Everything read/analyze/recommend: transactions, risk, AI recommendations, policy evaluation, audit, metrics, observability |
| `MERCHANT_ADMIN` | Everything `OPERATOR` can, plus `POST /api/recovery/{id}/execute` — the one endpoint that can cause a real (or simulated) payment-gateway call |

A request without a token gets `401 {"error": "Authentication required."}`.
An authenticated request without the required role gets
`403 {"error": "You do not have permission to perform this action."}`. Both
checks happen in Spring Security's filter chain, before any controller or
service code runs — there is no endpoint that skips this by being unmapped
(an unmapped path still requires authentication first) or by taking a
different HTTP verb.

## Implemented

### `GET /api/health`

Application-level liveness check (separate from Spring Boot Actuator's
`/actuator/health`, which is also exposed).

**Response `200 OK`**

```json
{
  "status": "UP",
  "service": "recoverai-backend",
  "timestamp": "2026-08-24T11:40:46.192816900Z"
}
```

### `GET /api/transactions` — general-purpose transaction dashboard (Phase 13)

Filterable, searchable, sortable, paginated listing over **every**
transaction in the database — not the 5 curated demo scenarios. See
`com.recoverai.transaction.TransactionDashboardService`.

**Query parameters** (all optional; combinable)

| Param | Notes |
|---|---|
| `status` | `SUCCESS`, `FAILED`, `PENDING`, `ABANDONED`, `RECOVERED`, `ESCALATED`, `STOPPED` |
| `riskLevel` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` — matches the transaction's current `RevenueRisk` row, if any |
| `failureCategory` | e.g. `TEMPORARY_FAILURE`, `BANK_DECLINED`, ... (see `FailureCategory`) |
| `paymentMethod` | `CARD`, `UPI`, `NETBANKING`, `WALLET`, `EMI` |
| `minAmount`, `maxAmount` | Inclusive transaction-amount range |
| `search` | Case-insensitive substring match on `externalTransactionId`, **or** an exact match if the value parses as a UUID (against the transaction's own id or its customer's id) |
| `atRiskOnly` | `true` restricts to transactions with a positive `amountAtRisk` |
| `recoveredOnly` | `true` restricts to `status=RECOVERED` |
| `recoveryAttemptStatus` | Matches a transaction whose **latest** recovery attempt (highest `attemptNumber`) is in this status — fixed in the production-readiness phase; previously matched "any attempt ever in this status" |
| `sort` | `NEWEST` (default), `OLDEST`, `AMOUNT_DESC`, `RISK_SCORE_DESC`, `AMOUNT_AT_RISK_DESC`, `RECOVERY_PROBABILITY_DESC` |
| `page`, `size` | Standard Spring Data pagination; `size` defaults to 20 |

**Response `200 OK`** — a Spring Data `Page<TransactionListItemResponse>`. Risk and recovery fields are `null` — not a fabricated `0` or guessed value — for a transaction nobody has analyzed/attempted recovery on yet:

```json
{
  "content": [
    {
      "id": "b7e6...",
      "externalTransactionId": "demo-easy-recovery",
      "amount": 2499.00,
      "currency": "INR",
      "status": "FAILED",
      "paymentMethod": "CARD",
      "failureCode": "TEMPORARY_FAILURE",
      "attemptCount": 1,
      "createdAt": "2026-08-22T10:15:00Z",
      "riskScore": 23.39,
      "riskLevel": "LOW",
      "recoveryProbability": 0.9324,
      "amountAtRisk": 2499.00,
      "latestRecoveryAction": "RETRY_PAYMENT",
      "latestRecoveryStatus": "SUCCESS",
      "latestRecoveryAt": "2026-08-24T20:46:46Z"
    }
  ],
  "totalElements": 500,
  "totalPages": 25
}
```

### `GET /api/transactions/{id}`

The minimal single-transaction projection (unchanged, Phase 2/10) — used by
the interactive console's "Refresh transaction" action. **Response `200 OK`**
— a `TransactionDetailResponse` (`merchantId`, `customerEmail` (masked,
Phase 10), `failureCode`, `failureReason`, `updatedAt`, ...). **Response
`404 Not Found`** if no transaction with that ID exists.

### `GET /api/transactions/{id}/detail` — full dashboard detail view (Phase 13)

One bundled fetch for the dashboard's transaction detail page: transaction,
customer history, revenue risk (`null` if never analyzed), the full
recovery attempt history (including each attempt's payment-confirmation
state — see below), and the complete audit timeline. **Response `404 Not
Found`** if no transaction with that ID exists.

**Response `200 OK`**

```json
{
  "transaction": { "id": "b7e6...", "externalTransactionId": "demo-easy-recovery", "...": "..." },
  "customerSuccessfulPaymentCount": 7,
  "customerFailedPaymentCount": 1,
  "customerTotalHistoricalValue": 18450.00,
  "customerRecoveryContactAllowed": true,
  "risk": { "riskScore": 23.39, "riskLevel": "LOW", "...": "..." },
  "recoveryAttempts": [
    {
      "id": "c1a2...", "action": "RETRY_PAYMENT", "attemptNumber": 1, "status": "SUCCESS",
      "provider": "mock", "providerReference": "mock_b7e6...:RETRY_PAYMENT:1", "simulated": true,
      "amount": 2499.00, "amountRecovered": 0.00,
      "paymentConfirmationStatus": "NOT_CONFIRMED", "confirmedAmount": null, "providerPaymentId": null, "confirmedAt": null,
      "executedAt": "2026-08-24T20:46:46Z"
    }
  ],
  "auditTimeline": [ { "id": "...", "eventType": "RISK_DETECTED", "...": "..." } ]
}
```

**Phase 10 data-minimization** still applies: `customerEmail` inside
`transaction` is partially masked (e.g. `j***e@example.com`) before it ever
leaves the server. See [README.md § PII / data-minimization review](../README.md).

`customerRecoveryContactAllowed` (Phase 14) reflects the customer's
consent flag — when `false`, `RecoveryPolicyService` blocks every
autonomous recovery action for this customer's transactions (`BLOCK`,
check name `CUSTOMER_CONSENT`), regardless of AI recommendation, before
any action-specific policy check runs. Server-side only; no endpoint
accepts a client-supplied override.

### Revenue Risk Engine (Phase 3)

All of these are deterministic — no AI/LLM call is made. See
[README.md § Revenue Risk Engine](../README.md)
for the scoring model itself.

#### `POST /api/revenue-risk/analyze/{transactionId}`

Analyzes one transaction and creates or updates its `RevenueRisk` record
(idempotent — re-analyzing updates the existing row, never duplicates).
**Response `404 Not Found`** if the transaction doesn't exist.

**Response `200 OK`**

```json
{
  "transactionId": "b7e6...",
  "externalTransactionId": "demo-high-value",
  "amount": 47500.00,
  "currency": "INR",
  "amountAtRisk": 47500.00,
  "riskScore": 68.19,
  "riskLevel": "HIGH",
  "recoveryProbability": 0.6824,
  "potentialRecoveryValue": 32418.10,
  "factors": ["INSUFFICIENT_FUNDS", "AMOUNT_AT_RISK", "HIGH_TRANSACTION_VALUE", "STRONG_CUSTOMER_HISTORY"],
  "reason": "High-value failed transaction creates significant revenue exposure.",
  "analyzedAt": "2026-08-24T18:21:22Z"
}
```

Note `riskScore` (0-100, how much/urgent the exposure is) and
`recoveryProbability` (0.0-1.0, how likely recovery is) are independent —
this example is simultaneously HIGH risk and has a 68% recovery
probability. See the "Risk score vs. recovery probability" note in the
README.

#### `POST /api/revenue-risk/analyze-all`

Batch-analyzes every transaction currently in a revenue-loss state
(`FAILED`, `PENDING`, `ABANDONED`, `ESCALATED`, `STOPPED`), then returns
the resulting aggregate metrics. Also corrects any stale risk row left
over from a transaction that has since resolved (e.g. `RECOVERED`).

**Response `200 OK`**

```json
{
  "transactionsAnalyzed": 197,
  "metrics": { "...": "same shape as GET /api/revenue-risk/metrics" }
}
```

#### `GET /api/revenue-risk/metrics`

Batch-level metrics computed via database aggregate queries over
persisted `RevenueRisk` rows (never loaded fully into memory, never
hardcoded). Reflects whatever was last analyzed — call `analyze-all`
first for it to be current.

```json
{
  "totalTransactions": 500,
  "atRiskTransactions": 197,
  "totalTransactionValue": 6536636.72,
  "totalRevenueCollected": 4012199.24,
  "revenueAtRisk": 2524437.48,
  "highRiskRevenue": 1491540.32,
  "criticalRiskRevenue": 241140.39,
  "averageRecoveryProbability": 0.4592,
  "potentiallyRecoverableRevenue": 1151129.02
}
```

#### `GET /api/revenue-risk/{transactionId}`

Returns the existing risk record for a transaction (same shape as the
`analyze` response). **Response `404 Not Found`** if it hasn't been
analyzed yet.

#### `GET /api/revenue-risk`

Paginated list of all `RevenueRisk` records, optionally filtered by
`?riskLevel=LOW|MEDIUM|HIGH|CRITICAL`. Standard `page`/`size` params.

### Recovery Safety / Policy Engine (Phase 4)

Deterministic — no AI/LLM call is made, and this endpoint never executes a
recovery action. See
[README.md § Recovery Safety / Policy Engine](../README.md)
for the full check pipeline and demo-scenario outcomes.

#### `POST /api/recovery-policy/evaluate/{transactionId}`

Evaluates one proposed `RecoveryAction` against a transaction's
authoritative database state and returns a decision — evaluation only,
no side effects other than a deduplicated audit-log write.
**Response `404 Not Found`** if the transaction doesn't exist.
**Response `400 Bad Request`** if `action` is missing or not a valid
`RecoveryAction` (`RETRY_PAYMENT`, `CREATE_PAYMENT_LINK`,
`SEND_RECOVERY_REMINDER`, `ESCALATE`, `STOP`).

Request:

```json
{ "action": "RETRY_PAYMENT" }
```

**Response `200 OK`** — high-value example (amount exceeds the
autonomous recovery limit, so this escalates for human approval even
though the transaction's own recovery probability is good):

```json
{
  "transactionId": "b7e6...",
  "externalTransactionId": "demo-high-value",
  "action": "RETRY_PAYMENT",
  "decision": "ESCALATE",
  "requiresHumanApproval": true,
  "reason": "Transaction amount of 47500.00 exceeds the autonomous recovery limit of 25000; human approval is required.",
  "policyChecks": [
    { "name": "TRANSACTION_STATUS", "passed": true, "reason": "Transaction status (FAILED) permits recovery evaluation." },
    { "name": "ACTION_COMPATIBILITY", "passed": true, "reason": "Retry is a valid action for a FAILED transaction." },
    { "name": "RETRY_LIMIT", "passed": true, "reason": "0 of 2 automatic retry attempts used." },
    { "name": "REPEATED_FAILURE", "passed": true, "reason": "0 of 3 total recovery actions used for this transaction." },
    { "name": "AMOUNT_LIMIT", "passed": false, "reason": "Transaction amount 47500.00 is above the autonomous recovery limit of 25000." }
  ],
  "evaluatedAt": "2026-08-24T18:51:23Z"
}
```

`policyChecks` is variable-length by design — checks after the one that
determined the outcome are never evaluated, so a `BLOCK` on an already-
`RECOVERED` transaction returns just the single `TRANSACTION_STATUS`
check. Full check order (Phase 14 adds `CUSTOMER_CONSENT` as the second
check, right after transaction status): `TRANSACTION_STATUS` →
`CUSTOMER_CONSENT` → `ACTION_COMPATIBILITY` → `RETRY_LIMIT` (RETRY_PAYMENT
only) → `REPEATED_FAILURE` → `AMOUNT_LIMIT` → `DUPLICATE_ACTION` →
`COOLDOWN` → `RISK_FLAGS`.

### AI Recovery Agent (Phase 5)

The AI only recommends — `com.recoverai.policy.RecoveryPolicyService`
(unchanged from Phase 4) remains the sole authorization boundary; neither
endpoint executes anything or calls Razorpay. See
[README.md § AI Recovery Agent](../README.md)
for the full pipeline, provider abstraction, and demo-scenario outcomes.

#### `POST /api/recovery-agent/evaluate/{transactionId}`

No request body — the server builds the recommendation context itself
from the transaction's authoritative database state and asks the
configured `AIRecoveryProvider` (`mock` by default) for a recommendation,
then runs it through the Phase 4 policy engine. **Response `404 Not Found`**
if the transaction doesn't exist.

**Response `200 OK`** — high-value example (the AI's recommendation is
overridden by policy purely on amount):

```json
{
  "transactionId": "b7e6...",
  "externalTransactionId": "demo-high-value",
  "aiRecommendation": {
    "action": "RETRY_PAYMENT",
    "confidence": 0.6824,
    "rationale": "Failure (INSUFFICIENT_FUNDS) has a reasonable recovery probability and limited retry history; recommending an automatic retry.",
    "interventionType": "RETRY",
    "expectedRecoveryValue": 32418.10,
    "urgency": "HIGH",
    "provider": "mock",
    "model": "recoverai-mock-v1",
    "providerAvailable": true
  },
  "policyDecision": {
    "transactionId": "b7e6...",
    "externalTransactionId": "demo-high-value",
    "action": "RETRY_PAYMENT",
    "decision": "ESCALATE",
    "requiresHumanApproval": true,
    "reason": "Transaction amount of 47500.00 exceeds the autonomous recovery limit of 25000; human approval is required.",
    "policyChecks": [{ "name": "AMOUNT_LIMIT", "passed": false, "reason": "..." }],
    "evaluatedAt": "2026-08-24T19:21:48Z"
  },
  "finalAction": "ESCALATE",
  "requiresHumanApproval": true,
  "expectedRecoveryValue": 32418.10,
  "auditEventId": "c1a2...",
  "evaluatedAt": "2026-08-24T19:21:48Z"
}
```

`finalAction` always reflects `policyDecision`, never the raw AI
recommendation when they differ — `null` when `policyDecision.decision ==
BLOCK` (no action is applicable). `expectedRecoveryValue` is a
**prediction**, never a claim that money was recovered.

#### `POST /api/recovery-agent/evaluate-all`

Batch AI recommendations over every currently at-risk transaction —
aggregated statistics only, no per-transaction results and no execution.

**Response `200 OK`**

```json
{
  "transactionsEvaluated": 197,
  "recommendationCountByAction": { "RETRY_PAYMENT": 58, "CREATE_PAYMENT_LINK": 69, "SEND_RECOVERY_REMINDER": 25, "ESCALATE": 30, "STOP": 15 },
  "countByPolicyDecision": { "ALLOW": 131, "ESCALATE": 51, "STOP": 15 },
  "averageConfidence": 0.6401,
  "providerFailures": 0,
  "malformedOutputs": 0
}
```

These are AI recommendation statistics, never "revenue recovered" — no
execution occurs anywhere in this phase.

### Recovery Execution Pipeline (Phase 7)

The only production-shaped execution endpoint - runs the full AI
recommendation → policy authorization → payment execution pipeline and
executes only when the fresh policy decision is `ALLOW`. See
[README.md § Recovery Execution Pipeline](../README.md)
for the full flow and the `amountRecovered` honesty rule.

#### `POST /api/recovery/{transactionId}/execute`

No request body - the server derives everything itself from the
transaction's authoritative database state; a client cannot supply or
override the action, amount, currency, or policy decision.
**Response `404 Not Found`** if the transaction doesn't exist.

**Response `200 OK`** — executed example (`demo-easy-recovery`):

```json
{
  "transactionId": "b7e6...",
  "externalTransactionId": "demo-easy-recovery",
  "recommendation": { "action": "RETRY_PAYMENT", "confidence": 0.9324, "provider": "mock", "...": "..." },
  "policyDecision": { "decision": "ALLOW", "requiresHumanApproval": false, "...": "..." },
  "requiresHumanApproval": false,
  "executed": true,
  "recoveryAttemptId": "c1a2...",
  "action": "RETRY_PAYMENT",
  "provider": "mock",
  "providerReference": "mock_b7e6...:RETRY_PAYMENT:1",
  "executionStatus": "SUCCESS",
  "amount": 2499.00,
  "amountRecovered": 0.00,
  "simulated": true,
  "failureCode": null,
  "failureReason": null,
  "duplicate": false,
  "executionNote": null,
  "auditEventId": "d2b3...",
  "executedAt": "2026-08-24T20:46:46Z",
  "paymentConfirmationStatus": "NOT_CONFIRMED",
  "confirmedAmount": null,
  "confirmedCurrency": null,
  "providerPaymentId": null,
  "confirmedAt": null
}
```

#### Execution status vs. payment confirmation

`executionStatus` and `paymentConfirmationStatus` are deliberately separate
fields answering two different questions:

| Field | Answers | Set by |
|---|---|---|
| `executionStatus` | Did the provider call itself go through (e.g. a payment link was created)? | `RecoveryExecutionService` (Phase 7), synchronously with this request |
| `paymentConfirmationStatus` | Did the customer actually pay? | `PaymentConfirmationService` (Phase 12), asynchronously, only from a verified Razorpay webhook |

`paymentConfirmationStatus` is one of `NOT_CONFIRMED` (the default — no
webhook has confirmed this attempt yet), `CONFIRMED` (a verified webhook
matched this exact attempt and its amount/currency), or `REJECTED` (a
verified webhook arrived but could not be trusted as confirmation of this
attempt — amount/currency mismatch, or the attempt was not a successful
execution). `amountRecovered` only becomes non-zero once
`paymentConfirmationStatus` is `CONFIRMED` — see
[Payment confirmation](#post-apiwebhooksrazorpay) below.

**Response `200 OK`** — not-executed example (`demo-high-value`, escalated on amount):

```json
{
  "transactionId": "...",
  "policyDecision": { "decision": "ESCALATE", "requiresHumanApproval": true, "...": "..." },
  "requiresHumanApproval": true,
  "executed": false,
  "recoveryAttemptId": null,
  "provider": null,
  "executionStatus": null,
  "amountRecovered": 0.00,
  "duplicate": false,
  "executionNote": null
}
```

`amountRecovered` is `0.00` for every result this phase can currently
produce - it is never inflated just because a payment link was created;
see the README section linked above. `executed=false` whenever
`policyDecision.decision` is not `ALLOW`, or the authorized action is not
a payment-gateway action (`executionNote` explains why in that case).
`duplicate=true` when this exact response reflects an already-completed
attempt rather than a fresh provider call.

There is deliberately no `POST /api/payments/execute` or similar endpoint
that would let a caller choose an arbitrary action.

`SEND_RECOVERY_REMINDER` (P0.2) is a real, auditable, non-payment
`RecoveryAttempt` (`executionStatus=SUCCESS`, `provider=null`,
`amountRecovered=0.00`), not a silent no-op — but `executed` stays `false`
since no `PaymentGateway` call happened; see `executionNote`.

#### `POST /api/recovery/{transactionId}/approve` and `.../reject` (P1.1, `MERCHANT_ADMIN` only)

Only valid when the transaction is currently `ESCALATED`
(`409 Conflict` otherwise). **Approving never itself authorizes
execution** — it lifts the transaction back to `FAILED` and then calls
the exact same `execute` pipeline above, so the AI recommendation and
the *entire* policy check chain (retry limit, repeated-failure cap,
amount limit, duplicate-action, cooldown, risk flags) run fresh. If
still not authorized, it escalates or blocks again and nothing executes.
Response shape is the same `RecoveryExecutionResponse` as `execute`.
Rejecting takes an optional `{"reason": "..."}` body, leaves the
transaction `ESCALATED`, and only records an audit event.

#### `POST /api/recovery/batch/execute` (Phase 14, `MERCHANT_ADMIN` only)

Bounded batch execution — the only multi-transaction execution endpoint.
Deliberately not "execute everything": every id is bounded by a
configurable maximum count and a configurable maximum aggregate monetary
amount, reloaded fresh from the database, and re-run through the exact
same AI + policy + execution pipeline `execute` above uses (no parallel
shortcut path). The client selects only which transactions to consider —
never their amount, action, or authorization.

Request:

```json
{ "transactionIds": ["b7e6...", "c1a2...", "b7e6..."] }
```

**Response `400 Bad Request`** if the request is empty, or if the number
of *distinct* ids exceeds `recoverai.policy.max-batch-transaction-count`
(default 20) — the whole request is rejected, never silently truncated.

**Response `200 OK`**

```json
{
  "totalRequested": 3,
  "distinctCount": 2,
  "duplicateRequestCount": 1,
  "executedCount": 1,
  "failedProviderCallCount": 0,
  "alreadyExecutedCount": 0,
  "blockedCount": 0,
  "escalatedCount": 1,
  "stoppedCount": 0,
  "skippedPortfolioLimitCount": 0,
  "notFoundCount": 0,
  "aggregateAmountExecuted": 2499.00,
  "maxAggregateAmount": 100000,
  "maxTransactionCount": 20,
  "results": [
    { "transactionId": "b7e6...", "externalTransactionId": "demo-easy-recovery", "outcome": "EXECUTED", "policyDecision": "ALLOW", "finalAction": "RETRY_PAYMENT", "recoveryAttemptId": "d2b3...", "amount": 2499.00, "reason": null },
    { "transactionId": "c1a2...", "externalTransactionId": "demo-high-value", "outcome": "ESCALATED", "policyDecision": "ESCALATE", "finalAction": null, "recoveryAttemptId": null, "amount": 47500.00, "reason": "Transaction amount of 47500.00 exceeds the autonomous recovery limit of 25000; human approval is required." }
  ]
}
```

Duplicate ids in the request are collapsed before processing (a
transaction is never executed twice within one batch call).
`SKIPPED_PORTFOLIO_LIMIT` means the policy would have allowed it, but
executing it would have exceeded `maxAggregateAmount` — skipped without
ever calling the provider (audited with its own event,
`RECOVERY_BATCH_SKIPPED_PORTFOLIO_LIMIT`), never partially exceeding the
ceiling. `executedCount`/`aggregateAmountExecuted` are provider-execution
figures, exactly like single-transaction `execute` — still not confirmed
revenue; only a subsequent webhook confirmation moves that.

#### `POST /api/demo/recovery/confirm-test-payment/{transactionId}` (P0.4, `MERCHANT_ADMIN` only)

The judge-safe way to see the confirmation pipeline produce a real,
non-zero `confirmedRecoveredRevenue` without real Razorpay credentials.
Builds a `payment_link.paid` payload from an already-executed mock
attempt's own amount/currency/provider-reference, signs it with the real
configured `RAZORPAY_WEBHOOK_SECRET`, and feeds it into the exact same
`PaymentConfirmationService.processRazorpayWebhook` a genuine inbound
webhook would hit. Requires `DEMO_SEED_ENABLED=true`,
`RAZORPAY_ENABLED=false`, and an eligible attempt (mock provider, not yet
confirmed) — `409 Conflict` otherwise, with a plain-language reason.
Response always includes `"label"` stating this is a TEST/SIMULATION.

### Failure-Recovery Demo (Phase 8)

A read/aggregation layer over the real Phase 3-7 pipeline, run against the
5 fixed named demo transactions — no new risk/AI/policy/payment decision
logic. See
[README.md § Failure-Recovery Demo](../README.md)
for the full design and the exact per-scenario expected outcomes.
Intentionally `GET`, even though calling it re-runs the real pipeline
(risk re-analysis, and `RecoveryExecutionService.execute()` exactly as
Phase 7's own endpoint would) — see the README for why no reset endpoint
is needed for repeatability.

#### `GET /api/demo/recovery`

**Response `200 OK`** — aggregate metrics plus all 5 scenarios:

```json
{
  "scenariosEvaluated": 5,
  "atRiskScenarios": 4,
  "allowedCount": 1,
  "blockedCount": 1,
  "escalatedCount": 2,
  "stoppedCount": 1,
  "executedCount": 1,
  "gatewayCalls": 1,
  "simulatedExecutions": 1,
  "totalAmountAtRisk": 152496.00,
  "totalPotentialRecoveryValue": 78421.55,
  "confirmedAmountRecovered": 0.00,
  "scenarios": [ "...": "one RecoveryDemoScenarioResponse per scenario, see below" ]
}
```

`confirmedAmountRecovered` is summed only from real `amountRecovered`
figures — never from `totalPotentialRecoveryValue`, `totalAmountAtRisk`,
or `executedCount` — so it stays `0.00` with today's mock/Razorpay
gateways.

#### `GET /api/demo/recovery/{externalTransactionId}`

One of `demo-easy-recovery`, `demo-high-value`, `demo-repeated-failure`,
`demo-successful-recovery`, `demo-retry-escalation`. **Response
`404 Not Found`** for any other id.

**Response `200 OK`** — `demo-easy-recovery` (executed):

```json
{
  "scenarioLabel": "EASY_RECOVERY",
  "transactionId": "b7e6...",
  "externalTransactionId": "demo-easy-recovery",
  "transactionStatus": "FAILED",
  "amount": 2499.00,
  "currency": "INR",
  "riskScore": 41.20,
  "riskLevel": "MEDIUM",
  "amountAtRisk": 2499.00,
  "recoveryProbability": 0.7912,
  "potentialRecoveryValue": 1977.21,
  "riskFactors": ["TEMPORARY_FAILURE", "AMOUNT_AT_RISK", "STRONG_CUSTOMER_HISTORY"],
  "riskReason": "Temporary failure with strong customer history creates a high recovery opportunity.",
  "aiRecommendedAction": "RETRY_PAYMENT",
  "aiConfidence": 0.9324,
  "aiRationale": "Temporary failure with strong customer history and no prior attempts; recommending an automatic retry.",
  "policyDecision": "ALLOW",
  "policyReason": "All policy checks passed; recovery action is authorized.",
  "requiresHumanApproval": false,
  "finalAction": "RETRY_PAYMENT",
  "executed": true,
  "executionStatus": "SUCCESS",
  "provider": "mock",
  "simulated": true,
  "amountRecovered": 0.00,
  "failureCode": null,
  "duplicate": false,
  "safetyExplanation": "AI recommended an action; the policy engine authorized it (ALLOW) and it was executed through the mock payment provider (simulated=true). This confirms the provider call ran — not that money was recovered. Payment confirmation is pending; amountRecovered stays 0.00 until a real, confirmed provider result exists.",
  "auditTimeline": [
    { "id": "...", "eventType": "RISK_DETECTED", "actor": "SEED_SCRIPT", "decision": "N/A", "reason": "Synthetic seed record - transaction flagged as revenue at risk", "timestamp": "..." },
    { "id": "...", "eventType": "RECOVERY_POLICY_EVALUATED", "actor": "POLICY_ENGINE", "decision": "ALLOW", "reason": "...", "timestamp": "..." },
    { "id": "...", "eventType": "RECOVERY_AI_RECOMMENDATION", "actor": "AI_AGENT", "decision": "RETRY_PAYMENT", "reason": "...", "timestamp": "..." },
    { "id": "...", "eventType": "RECOVERY_EXECUTION_STARTED", "actor": "RECOVERY_EXECUTION_SERVICE", "decision": "ALLOW", "reason": "...", "timestamp": "..." },
    { "id": "...", "eventType": "RECOVERY_EXECUTION_COMPLETED", "actor": "RECOVERY_EXECUTION_SERVICE", "decision": "ALLOW", "reason": null, "timestamp": "..." }
  ]
}
```

`auditTimeline` contains only real, persisted `AuditLog` rows — nothing is
synthesized by this endpoint. For a transaction seeded with prior history
(as in this example) that includes the seeder's own historical
`RISK_DETECTED`/`RECOVERY_ATTEMPT_RECORDED` rows (`actor=SEED_SCRIPT`),
not just the live `RECOVERY_AI_RECOMMENDATION`/`RECOVERY_POLICY_EVALUATED`/
`RECOVERY_EXECUTION_*` rows this call itself produces. There is still no
*live* `RISK_ANALYZED` entry, since Phase 3's `RevenueRiskService` does
not itself write to the audit trail (see README § Known limitations —
Phase 8).

### Audit Trail

#### `GET /api/audit/{transactionId}`

**Response `200 OK`** — the transaction's real, persisted `AuditLog` rows
in chronological order (an array of `AuditTimelineEntryResponse`, the same
shape used inside the demo endpoint's `auditTimeline` field above), or an
empty array if none exist yet. **Response `404 Not Found`** if the
transaction doesn't exist. Pure read - no side effects, unlike
`GET /api/demo/recovery`/`GET /api/demo/recovery/{externalTransactionId}`
(which re-run the evaluate/execute pipeline as a side effect of viewing).
Added so the interactive frontend console can refresh a transaction's
audit trail independently, for any transaction, not just the 5 curated
demo scenarios.

#### `GET /api/audit` (P1.4)

Portfolio-wide, paginated audit feed across every transaction, not just
one already-known id — same real `AuditLog` rows, each additionally
carrying `transactionId`/`externalTransactionId`. Optional query params:
`eventType`, `actor`, `transactionId`, `from`/`to` (ISO instants), plus
standard `page`/`size`/`sort` (default: newest first, `size=25`, capped
at 100). No `metadata` field is exposed here, same as the per-transaction
timeline above.

### Payment confirmation (Phase 12)

See `com.recoverai.webhook.PaymentConfirmationService` and
[docs/ARCHITECTURE.md § Payment Confirmation](ARCHITECTURE.md) for the full
design — signature verification, correlation, amount/currency
verification, and idempotency.

#### `POST /api/webhooks/razorpay`

Inbound Razorpay webhook receiver. This is the **only** path in the system
that can transition a transaction to `RECOVERED` or set a non-zero
confirmed amount — there is deliberately no `POST /api/recovery/{id}/confirm`
or any other endpoint that lets a client mark a payment recovered directly.

**Request** — the raw webhook body Razorpay sends, plus headers
`X-Razorpay-Signature` (required) and `X-Razorpay-Event-Id` (optional —
used for idempotency when present; a deterministic fallback derived from
the event type and payment/payment-link id is used otherwise).

**Response `400 Bad Request`** if the signature is missing or invalid —
the payload is never parsed or acted on in this case:

```json
{ "error": "Invalid webhook signature." }
```

**Response `200 OK`** for every signature-verified delivery, regardless of
whether it resulted in a confirmation — Razorpay should not retry a
delivery that was received and understood, only ones that never reached
the server:

```json
{ "status": "CONFIRMED", "reason": "Payment confirmed; transaction marked RECOVERED." }
```

`status` is one of `CONFIRMED`, `REJECTED` (verified but could not be
trusted as confirmation of a specific attempt — reason explains why),
`IGNORED` (an event type this system does not act on), or
`ALREADY_PROCESSED` (a duplicate or concurrently-raced delivery of an
event already handled — no state changed again). The response body never
contains the webhook secret, the signature, or the raw payload.

#### `GET /api/recovery/metrics`

Portfolio-level recovery metrics, computed via database aggregates across
every `RecoveryAttempt` ever created — not just the 5 demo scenarios.

**Response `200 OK`**

```json
{
  "totalRevenueAtRisk": 1234567.89,
  "potentiallyRecoverableRevenue": 654321.00,
  "recoveryAttempts": 42,
  "successfulExecutionCount": 30,
  "confirmedRecoveryCount": 0,
  "confirmedRecoveredRevenue": 0.00,
  "recoveryRate": 0.0000,
  "executionSuccessRate": 0.7143,
  "confirmationRate": 0.0000,
  "pendingConfirmationAmount": 74970.00,
  "amountRemainingAtRisk": 1159597.89,
  "transactionsRecovered": 0,
  "transactionsEscalated": 6,
  "transactionsStopped": 4,
  "distinctCustomersProcessed": 27
}
```

`confirmedRecoveredRevenue` is summed only from attempts a verified webhook
confirmed — never from execution success or `potentiallyRecoverableRevenue`.
With the default mock provider (or an unconfigured Razorpay), it is
honestly `0.00`, as shown above. `pendingConfirmationAmount` is the sum of
`amount` across attempts that executed successfully but have not yet been
confirmed — money genuinely "in flight." `amountRemainingAtRisk` (P1.3) is
`max(0, totalRevenueAtRisk - confirmedRecoveredRevenue)` — never negative,
never double-counted. `successfulExecutionCount` and
`pendingConfirmationAmount` are both restricted to rows a real
`PaymentGateway` call produced (`provider IS NOT NULL`) — a recorded
`SEND_RECOVERY_REMINDER` never inflates either figure.
`transactionsEscalated`/`transactionsStopped` (P0.1) now reflect the live
pipeline's own `Transaction.status` transitions, not just seed data.
`distinctCustomersProcessed` (Phase 14) counts distinct customers with at
least one `RecoveryAttempt` — customers the system has actually acted on,
not merely customers with an at-risk transaction.

#### `GET /api/observability/metrics` (production readiness phase)

Policy decision, webhook processing, and provider call counts — separate
from the revenue-focused metrics above. See
[docs/ARCHITECTURE.md § Production Observability](ARCHITECTURE.md) for
exactly where each field comes from (database aggregate vs. the two
in-memory counters).

**Response `200 OK`**

```json
{
  "policyDecisions": { "allow": 30, "block": 4, "escalate": 6, "stop": 4 },
  "webhooks": {
    "receivedTotal": 12,
    "processed": 10,
    "rejected": 1,
    "ignored": 0,
    "invalidSignature": 1,
    "malformedPayload": 0
  },
  "providers": [
    { "provider": "mock", "status": "SUCCESS", "total": 30 },
    { "provider": "mock", "status": "FAILED", "total": 2 }
  ],
  "aiProviderMode": "mock"
}
```

`aiProviderMode` (Phase 14) is the actual configured
`recoverai.ai.provider` value (`"mock"` or `"anthropic"`), read directly
from configuration — never inferred, never hardcoded to imply more than
what's actually running.

## Planned (not yet implemented)

Per the product spec, the following endpoints will be added in later
phases:

- `GET /api/dashboard/summary`
- `POST /api/recovery/simulate-failure/{transactionId}`
- `POST /api/demo/seed`

`POST /api/recovery/execute/{transactionId}` was implemented in Phase 7 as
`POST /api/recovery/{transactionId}/execute` (see above). `POST
/api/demo/reset` was deliberately not built in Phase 8 — see
[README.md § Known limitations — Phase 8](../README.md)
for why it isn't needed.
