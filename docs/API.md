# API reference

Status: Phase 1 (foundation), Phase 2 (domain + database), Phase 3
(revenue risk engine), Phase 4 (recovery safety / policy engine), Phase 5
(AI recovery agent), Phase 6 (Razorpay integration / payment adapter),
Phase 7 (recovery execution pipeline), Phase 8 (failure-recovery demo),
Phase 9 (production deployment), Phase 10 (audit, compliance & production
hardening), Phase 11 (interactive recovery console), and Phase 12 (payment
confirmation, webhook verification & measured revenue recovery) complete —
the API described below is live at https://recoverai-xrky.onrender.com.
Endpoints are documented here as they are implemented; see
[README.md](../README.md) for overall phase progress and
[README.md § Live deployment](../README.md) for
the deployment record.

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

### `GET /api/transactions`

Minimal, paginated transaction listing — added to prove the persistence
layer works over HTTP, not a finished dashboard API (no filtering beyond
status, no sorting options exposed yet).

**Query parameters**

| Param | Required | Notes |
|---|---|---|
| `status` | No | One of `SUCCESS`, `FAILED`, `PENDING`, `ABANDONED`, `RECOVERED`, `ESCALATED`, `STOPPED` |
| `page`, `size` | No | Standard Spring Data pagination; `size` defaults to 20 |

**Response `200 OK`** — a Spring Data `Page<TransactionSummaryResponse>`:

```json
{
  "content": [
    {
      "id": "b7e6...",
      "externalTransactionId": "demo-easy-recovery",
      "customerId": "a1c2...",
      "customerName": "Demo Customer 7",
      "amount": 2499.00,
      "currency": "INR",
      "status": "FAILED",
      "paymentMethod": "CARD",
      "attemptCount": 1,
      "createdAt": "2026-08-22T10:15:00Z"
    }
  ],
  "totalElements": 500,
  "totalPages": 25
}
```

### `GET /api/transactions/{id}`

**Response `200 OK`** — a `TransactionDetailResponse` (adds `merchantId`,
`customerEmail`, `failureCode`, `failureReason`, `updatedAt` over the
summary shape above). **Response `404 Not Found`** if no transaction with
that ID exists.

**Phase 10:** `customerEmail` is partially masked (e.g.
`j***e@example.com`) before it ever leaves the server — this endpoint has
no authentication and nothing in this project's frontend currently reads
the raw address. See [README.md § PII / data-minimization review](../README.md).

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
check.

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
  "pendingConfirmationAmount": 74970.00
}
```

`confirmedRecoveredRevenue` is summed only from attempts a verified webhook
confirmed — never from execution success or `potentiallyRecoverableRevenue`.
With the default mock provider (or an unconfigured Razorpay), it is
honestly `0.00`, as shown above. `pendingConfirmationAmount` is the sum of
`amount` across attempts that executed successfully but have not yet been
confirmed — money genuinely "in flight."

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
