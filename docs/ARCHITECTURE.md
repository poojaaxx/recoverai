# Architecture

Status: Phase 1 (foundation), Phase 2 (domain + database), Phase 3
(revenue risk engine), Phase 4 (recovery safety / policy engine), Phase 5
(AI recovery agent), Phase 6 (Razorpay integration / payment adapter),
Phase 7 (recovery execution pipeline), Phase 8 (failure-recovery demo),
Phase 9 (production deployment), Phase 10 (audit, compliance & production
hardening), and Phase 11 (interactive recovery console) complete — this
architecture is deployed and live (Neon PostgreSQL, Render backend,
Vercel frontend; see
[README.md § Live deployment](../README.md#12-live-deployment-phase-9)).
This document will be filled in further as later phases (batch execution,
recovery metrics, dashboard) land, so that it always accurately reflects
what is actually implemented rather than the target design.

## Current state

```
frontend/ (Vite + React + TS, port 5173)
   |
   |  GET /api/health
   |  GET /api/transactions, /api/transactions/{id}
   |  /api/revenue-risk/* (analyze, analyze-all, metrics, list, get)
   |  POST /api/recovery-policy/evaluate/{transactionId}
   |  POST /api/recovery-agent/evaluate/{transactionId}, /evaluate-all
   |  POST /api/recovery/{transactionId}/execute
   |  GET /api/demo/recovery, /api/demo/recovery/{externalTransactionId}
   v
backend/ (Spring Boot, port 8080)
   |                                        \
   v                                         v
PostgreSQL (docker-compose, port 5432)    PaymentGateway -> Razorpay (Test Mode, opt-in) / Mock (default)
                                           (now reachable via /api/recovery/{id}/execute - see Phase 7 below)
```

- `backend/src/main/java/com/recoverai/controller/HealthController.java`
  exposes `GET /api/health` as an application-level liveness check,
  distinct from Spring Boot Actuator's `/actuator/health`.
- `backend/src/main/java/com/recoverai/controller/TransactionController.java`
  exposes minimal read-only transaction listing/detail endpoints — added
  only to prove the persistence layer works over HTTP, not a finished API.
- `backend/src/main/java/com/recoverai/controller/RevenueRiskController.java`
  exposes the Phase 3 risk-analysis endpoints — see
  [Revenue Risk Engine](#revenue-risk-engine-phase-3) below.
- `backend/src/main/java/com/recoverai/controller/RecoveryPolicyController.java`
  exposes the Phase 4 policy-evaluation endpoint — see
  [Recovery Safety / Policy Engine](#recovery-safety--policy-engine-phase-4)
  below.
- `backend/src/main/java/com/recoverai/controller/RecoveryAgentController.java`
  exposes the Phase 5 AI-recommendation endpoints — see
  [AI Recovery Agent](#ai-recovery-agent-phase-5) below.
- `backend/src/main/java/com/recoverai/payment/` (Phase 6) still has no
  controller of its own, deliberately - see
  [Razorpay Integration / Payment Adapter](#razorpay-integration--payment-adapter-phase-6)
  below.
- `backend/src/main/java/com/recoverai/controller/RecoveryExecutionController.java`
  (Phase 7) exposes the one execution endpoint - see
  [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7)
  below.
- `backend/src/main/java/com/recoverai/controller/RecoveryDemoController.java`
  (Phase 8) exposes the two read/aggregation demo endpoints - see
  [Failure-Recovery Demo](#failure-recovery-demo-phase-8) below.
- `backend/src/main/java/com/recoverai/config/WebConfig.java` configures
  CORS so the Vite dev server can call the API during local development.
- No batch execution, recovery metrics, or dashboard (beyond the Phase 8
  demo page) exist yet.

## Domain model (Phase 2)

```
Merchant
   |
   +-- Customer  (Merchant 1:N Customer)
   |      |
   |      +-- Transaction  (Customer 1:N Transaction)
   |               |
   |               +-- RevenueRisk       (Transaction 1:N RevenueRisk)
   |               +-- RecoveryAttempt   (Transaction 1:N RecoveryAttempt)
   |               +-- AuditLog          (Transaction 1:N AuditLog)
   |
   +-- Transaction  (Merchant 1:N Transaction, same rows as above — a
                      Transaction references both its Merchant and its
                      Customer directly, no denormalized duplication)
```

All associations are unidirectional `@ManyToOne` (child → parent only,
`FetchType.LAZY`) — there are no inverse `@OneToMany` collections on
`Merchant`, `Customer`, or `Transaction`. Code that needs "all X for this
Y" queries the relevant repository directly (e.g.
`CustomerRepository.findByMerchantId`). This keeps entity graphs simple
and avoids lazy-loading/serialization surprises; see the note on
`TransactionController` below for the one place that matters in practice.

Key modeling decisions:

- **UUID primary keys**, assigned in the JVM via Hibernate's
  `GenerationType.UUID` (not DB-generated) so the same code path works
  identically against PostgreSQL and H2. Migrations also set
  `DEFAULT gen_random_uuid()` at the column level as a safety net for any
  direct SQL, though JPA inserts always supply an explicit value.
- **Money is always `BigDecimal`**, mapped to `NUMERIC(14,2)` — never a
  floating-point type.
- **`TransactionStatus`, `PaymentMethod`, `RecoveryAction`,
  `RecoveryAttemptStatus`** are Java enums (`@Enumerated(EnumType.STRING)`),
  backed by `VARCHAR` + a `CHECK` constraint listing the allowed values in
  the migration — a fixed, small, internally-governed vocabulary.
- **`Transaction.failureCode` is a plain `String`**, not an enum column.
  The seed data generator uses a Java enum (`FailureCategory`) internally
  for its own synthetic taxonomy, but a real payment gateway's failure
  vocabulary is outside this application's control, so the column itself
  stays open-ended.
- **`AuditLog.metadata` is `JSONB`** on PostgreSQL (Hibernate 6's
  `@JdbcTypeCode(SqlTypes.JSON)` over a `Map<String, Object>`), for
  arbitrary structured context without a migration per new field.
- **`RecoveryAttemptStatus.BLOCKED` vs `FAILED`**: `FAILED` means an
  action was actually executed (against Razorpay or the simulation
  adapter, in later phases) and did not succeed; `BLOCKED` means the
  safety policy engine (Phase 4) rejected the action before execution —
  no external call was made. This distinction is what will let the audit
  trail distinguish "we tried and it didn't work" from "we refused to
  try."

## Database migrations

Eight Flyway migrations under `backend/src/main/resources/db/migration/`
(`V1` merchants → `V2` customers → `V3` transactions → `V4` revenue_risks
→ `V5` recovery_attempts → `V6` audit_logs → `V7` indexes → `V8` adds
`revenue_risks.risk_level`/`factors` and a uniqueness constraint on
`revenue_risks.transaction_id` for Phase 3) are the source of truth for
schema. Phases 4 and 5 introduced no migration — `RecoveryPolicyService`
and `RecoveryAgentService` only read existing `transactions`/
`recovery_attempts`/`revenue_risks` columns and write to the
already-existing `audit_logs` table; neither persists a new kind of fact
that would require a schema change. Phase 6 adds one migration (`V9`):
`recovery_attempts.idempotency_key`, `VARCHAR(200)`, with a unique
constraint (nullable, so historical rows are unaffected — PostgreSQL
treats multiple `NULL`s as distinct under a `UNIQUE` constraint) — see
[Razorpay Integration / Payment Adapter](#razorpay-integration--payment-adapter-phase-6).
Phase 7 adds `V10`: `recovery_attempts.amount` (`NUMERIC(14,2) NOT NULL`,
backfilled from the parent transaction for every existing row, `CHECK
(amount > 0)`) plus nullable `provider`/`provider_reference` — see
[Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7). Phase
8 introduces no migration — `RecoveryDemoService` only reads existing
tables via existing repository methods and writes nothing of its own; the
schema remains at `V10`. `spring.jpa.hibernate.ddl-auto` is `validate` in the default
(PostgreSQL) profile — Hibernate checks the migrated schema matches the
entity mappings exactly; it never creates or alters tables itself outside
of tests.

Indexes (`V7`) exist only on foreign-key columns and columns known to be
filtered/sorted on already (`transactions.status`, `transactions.created_at`,
`audit_logs.event_type`, `audit_logs.timestamp`) — PostgreSQL does not
auto-index foreign keys the way it does primary keys, so those are
explicit.

## Verifying against real PostgreSQL without Docker

This project was built in an environment without Docker or a local
PostgreSQL install. Rather than only verifying the schema against H2 (whose
PostgreSQL-compatibility mode is close but not exact — see the constraint
bug below), the backend test suite includes
`PostgresMigrationIntegrationTest`, which uses
`io.zonky.test:embedded-postgres` to start a real, temporary PostgreSQL
instance (a genuine `postgres.exe`, extracted and run by the JVM — not a
Docker container, not a mock) for the duration of that one test. It runs
the actual Flyway migrations and `ddl-auto: validate` against it, then
runs the seeder and asserts on the real, persisted result.

This is what caught a real bug during development: an early version of
the `V3__create_transactions.sql` migration had
`CHECK (attempt_count > 0)`, but `ABANDONED` transactions legitimately
have zero payment attempts. H2's looser constraint handling in the `test`
profile didn't catch this — real PostgreSQL, running via this embedded
instance, did (`23514` check-constraint violation). Fixed to
`CHECK (attempt_count >= 0)`.

This does not replace having a real, persistent PostgreSQL instance for
actually running the app (`docker-compose.yml`) — it only proves the
schema and mappings are correct.

## Seed data generator

`backend/src/main/java/com/recoverai/seed/DemoDataSeeder.java` is a small,
explicitly-scoped `@Service` — not a business service layer. See
[README.md § Dataset](../README.md#dataset) for what it generates and why
it is not the Phase 3/4 engines. `seed()` now wipes previously-seeded data
before regenerating (needed once a second Phase 3 test class also called
it against the same shared H2 database — see git history on
`DemoDataSeeder.resetAll()`), which incidentally also makes it safe
groundwork for a future `POST /api/demo/reset`.

## Revenue Risk Engine (Phase 3)

`backend/src/main/java/com/recoverai/risk/RevenueRiskService.java` is the
whole engine — see [README.md § Revenue Risk Engine](../README.md#revenue-risk-engine-phase-3)
for the formula, the risk-score-vs-recovery-probability distinction, and
worked numbers from the seed dataset. Architecturally relevant points not
covered there:

- **No AI, anywhere in this phase.** `RevenueRiskService` has no
  dependency on any LLM provider or HTTP client to an AI service — it is
  pure deterministic arithmetic over `RevenueRiskProperties` (a
  `@ConfigurationProperties` bean bound from `application.yml`'s
  `recoverai.risk.*`).
- **Batch performance:** `analyzeAllAtRisk()` avoids N+1 queries by (a)
  fetch-joining `transaction.customer` in one query
  (`TransactionRepository.findByStatusInWithCustomer`) instead of lazily
  loading each customer one at a time, and (b) counting prior failed
  `RecoveryAttempt` rows per transaction with a single grouped query
  (`RecoveryAttemptRepository.countFailedByTransactionIds`) instead of one
  count query per transaction.
- **Idempotent upsert:** `RevenueRiskService.persist()` looks up the
  existing row by `transaction_id` (unique since V8) and updates it in
  place; `detectedAt` therefore means "most recently analyzed," not
  "first detected."
- **Stale-row correction:** because the Phase 2 seed data pre-populates a
  risk row for `RECOVERED` transactions (a seed-heuristic label, not this
  engine's output — see [README.md § Dataset](../README.md#dataset)),
  `analyzeAllAtRisk()` also re-runs the (zeroing) computation over any
  `RESOLVED`-status transaction that already has a risk row, so aggregate
  metrics never overcount already-collected revenue as at-risk.
- **This service does not decide or execute anything.** It has no
  dependency on `RecoveryAttempt`-writing logic (Phase 4 territory) and
  no HTTP client to Razorpay or any AI provider — it only reads
  transaction/customer/recovery-attempt state and writes `RevenueRisk`
  rows.

## Recovery Safety / Policy Engine (Phase 4)

`backend/src/main/java/com/recoverai/policy/RecoveryPolicyService.java` is
the authorization boundary between a recovery recommendation and an
actually-executed recovery action — see
[README.md § Recovery Safety / Policy Engine](../README.md#recovery-safety--policy-engine-phase-4)
for the full check pipeline, configured thresholds, and demo-scenario
outcomes. Architecturally relevant points not covered there:

- **No AI, anywhere in this phase.** `RecoveryPolicyService` takes a
  `RecoveryAction` as a plain method parameter — it has no dependency on
  any LLM provider, and no opinion about *how* that action was chosen.
  This is deliberate: Phase 5's AI agent will call the exact same
  `evaluate()` method Phase 4's tests already call directly, so the
  authorization boundary does not change shape when the AI is wired in.
- **No side effects.** `evaluate()` never writes a `RecoveryAttempt` row
  and never calls Razorpay or any AI provider — it is read-and-decide
  only. The only write is an `AuditLog` row recording the decision itself
  (see below), which is what makes the boundary *audit-ready* without
  making it *action-taking*.
- **Authoritative inputs only.** Transaction status, amount,
  recovery-attempt history, and risk level are all loaded fresh from the
  database inside `evaluate()` by transaction ID — the only client-
  supplied input is the proposed `RecoveryAction` itself. A caller cannot
  spoof the facts a decision is based on (see `RecoveryPolicyServiceTest`'s
  "never trust client-supplied state" framing throughout its BLOCK/STOP
  tests, which construct transactions with a given DB state and never pass
  that state as a parameter).
- **Fixed-order, short-circuiting checks.** `TRANSACTION_STATUS` and
  `ACTION_COMPATIBILITY` run first and unconditionally, before any
  amount/retry/duplicate logic — an already-resolved, escalated, or
  stopped transaction is decided by those two checks alone, regardless of
  which action was proposed. This is why `policyChecks` in the response is
  variable-length: it only contains checks that were actually evaluated
  before the decision was reached.
- **`RETRY_LIMIT` vs `REPEATED_FAILURE`.** These are deliberately two
  separate checks rather than one: `RETRY_LIMIT` only counts prior
  `RETRY_PAYMENT` attempts (so it fires exactly at the seeded
  "2 failed retries then a terminal action" pattern in `DemoDataSeeder`),
  while `REPEATED_FAILURE` counts *all* recorded recovery actions of any
  type, so a transaction that mixes a retry, a reminder, and a payment
  link still gets stopped once the total exceeds
  `maxRecoveryActionsPerTransaction`, even though no single action type
  individually hit its own limit.
- **`AMOUNT_LIMIT` is independent of Phase 3's risk score by design.**
  "High value does not mean high risk" — a large, easily-recoverable
  transaction (high `recoveryProbability`) still requires human approval
  purely because of its amount; `RISK_FLAGS` is the only check that reads
  `RevenueRisk` at all, and only escalates on `CRITICAL` — a good risk
  level never by itself authorizes an `ALLOW`, it just declines to add an
  extra escalation reason.
- **Deduplicated audit trail.** `AuditLogRepository.
  findTopByTransactionIdAndEventTypeOrderByTimestampDesc` lets
  `recordAudit()` skip writing a new `RECOVERY_POLICY_EVALUATED` row when
  a re-evaluation reaches the same decision for the same action as the
  most recently recorded one — chosen over auditing every call because
  this evaluation endpoint has no side effects and is expected to be
  polled/repeated, and an audit trail that grows once per poll would not
  be usable in a demo. A changed decision (e.g. a transaction crossing its
  retry limit between two evaluations) always gets its own row.

## AI Recovery Agent (Phase 5)

`backend/src/main/java/com/recoverai/agent/RecoveryAgentService.java`
orchestrates context building, the AI provider call, output validation,
and handing the resulting action to Phase 4's `RecoveryPolicyService` —
see [README.md § AI Recovery Agent](../README.md#ai-recovery-agent-phase-5)
for the pipeline diagram, provider abstraction, recommendation schema, and
demo-scenario outcomes. Architecturally relevant points not covered there:

- **The AI is a pure recommender, structurally.** `AIRecoveryProvider.
  recommend(RecoveryAgentContext)` returns a `RecoveryRecommendation` and
  has no access to any repository, no way to persist anything, and no
  reference to `RecoveryPolicyService` — it cannot execute an action or
  influence authorization by any means other than the one field (`
  recommendedAction`) that `RecoveryAgentService` reads from it. This is
  what makes "the AI cannot bypass policy" true by construction, not just
  by convention.
- **One explicit wiring point.** `AIProviderConfig` is the single place
  that decides `MockAIRecoveryProvider` vs `AnthropicAIRecoveryProvider`
  from `recoverai.ai.provider` — chosen over two `@Component`-annotated
  providers with `@ConditionalOnProperty` specifically so there is no risk
  of an ambiguous-bean wiring error and no need to hunt across files to
  see how the choice is made.
- **Validation is exhaustive and fails closed.** `RecoveryAgentService.
  isValid()` rejects a null/unknown action, an out-of-range confidence, a
  negative expected value, a missing intervention type/urgency, a blank
  rationale, or a mismatched transaction id. Any provider exception or
  failed validation produces the *same* safe-fallback recommendation
  (`ESCALATE`, confidence 0) before policy evaluation runs — deliberately
  routed through the ordinary `ESCALATE`-action path in `
  RecoveryPolicyService` rather than a special "AI failed" code path, so
  the failure mode is exercised by the same, already-tested policy logic
  every other `ESCALATE` recommendation uses.
- **Context building has no compile-time dependency on Phase 3's
  internals.** `RecoveryAgentService.resolveFailureCategory()` and `
  MockAIRecoveryProvider`'s Bayesian-smoothed probability estimate are
  small, independent reimplementations of the same logic `
  RevenueRiskService` uses internally (rather than exposing those as
  public API on a service that Phase 3 already shipped and tested) — a
  deliberate choice to avoid modifying a working, verified service's
  visibility just to save a few lines here.
- **Audit-noise tradeoff, mirrored from Phase 4.** `evaluate()` (single
  transaction) always writes a `RECOVERY_AI_RECOMMENDATION` row;
  `evaluateAll()` (batch) does not, reusing the exact same per-transaction
  pipeline with a `writeAudit` flag — the same reasoning Phase 4 applied
  to its own policy-evaluation audit dedup, applied here to a different
  failure mode (call-volume flooding rather than repeated-identical-calls).
- **This service does not decide safety and does not execute anything.**
  It has no HTTP client to Razorpay and writes no `RecoveryAttempt` row —
  `finalAction` in its response is what the system *would* do, gated
  entirely by Phase 4, not a record of something that happened.

## Razorpay Integration / Payment Adapter (Phase 6)

`backend/src/main/java/com/recoverai/payment/` provides the execution
boundary Phase 7 will call - see
[README.md § Razorpay Integration / Payment Adapter](../README.md#razorpay-integration--payment-adapter-phase-6)
for the pipeline diagram, the RETRY_PAYMENT/CREATE_PAYMENT_LINK-to-Payment-Links
mapping, and the `amountRecovered` honesty guarantee. Architecturally
relevant points not covered there:

- **No production caller exists yet, by design.** `PaymentGateway` is
  fully implemented and tested, but nothing in `RecoveryAgentController`,
  `RecoveryAgentService`, or anywhere else calls it - there is no
  `POST /api/payments/...` endpoint. The Phase 6 spec is explicit that
  building "authorize via Phase 4, then execute" orchestration is Phase
  7's job; Phase 6 only has to prove the gateway itself is correct and
  cannot be reached except by an already-authorized caller (which does
  not exist yet).
- **Structural isolation, not just tested isolation.** `PaymentGateway`
  is never injected into `RecoveryAgentService` or `RecoveryPolicyService`
  - `RecoveryPipelineIsolationTest` asserts this via reflection over
  `getDeclaredFields()`, so the guarantee is "the AI/policy layers cannot
  call the gateway" rather than merely "the tests don't happen to call
  it."
- **`execute()` has a total-function contract.** Every implementation
  (`MockPaymentGateway`, `RazorpayPaymentGateway`) returns a
  `PaymentExecutionResult` for every input - it never throws for an
  ordinary provider failure. This was a deliberate departure from Phase
  5's pattern (where `AIRecoveryProvider` may throw and the caller
  catches it): a payment attempt always has a meaningful outcome worth
  representing structurally, so pushing failure-handling into the return
  type rather than exception handling keeps every caller's control flow
  uniform.
- **Shared validation, not duplicated per gateway.** `
  PaymentGatewayValidation` (package-private) is the single source of
  truth for "is this request well-formed" (action, amount, currency,
  required fields) - both `MockPaymentGateway` and `
  RazorpayPaymentGateway` call it first and return identical `
  INVALID_REQUEST` results for the same malformed input, so the two
  providers cannot silently diverge on what counts as a valid request.
- **`PaymentAuditEvents` is a pure builder, not a service.** It has no
  repository dependency and persists nothing - it exists purely so that
  whichever Phase 7 code eventually orchestrates execution can call
  `auditLogRepository.save(PaymentAuditEvents.forResult(transaction,
  result))` against an already-correct, already-tested event shape,
  rather than inventing the metadata structure under time pressure later.

## Recovery Execution Pipeline (Phase 7)

`backend/src/main/java/com/recoverai/execution/RecoveryExecutionService.java`
is the first component that actually wires AI recommendation, policy
authorization, and payment execution together — see
[README.md § Recovery Execution Pipeline](../README.md#recovery-execution-pipeline-phase-7)
for the full flow diagram, the `amountRecovered` honesty rule, and the
layered idempotency design. Architecturally relevant points not covered
there:

- **Orchestration only - no re-implemented safety logic.** The only
  policy-adjacent code this service contains is the `GATEWAY_ACTIONS`
  set (which two actions are payment operations, mirroring Phase 6's own
  `PaymentGatewayValidation`) and the `amountRecovered > 0` transaction-
  state rule. Every actual authorization decision comes from calling
  `RecoveryAgentService.evaluate()`, which itself calls `
  RecoveryPolicyService.evaluate()` fresh - so a policy change in Phase 4
  automatically applies here with no Phase 7 code change needed.
- **Programmatic transactions, not `@Transactional`, and why.** `
  RecoveryExecutionService.execute()` is deliberately *not*
  `@Transactional` - it uses a `TransactionTemplate` explicitly so a lost
  concurrency race (a `DataIntegrityViolationException` from the
  idempotency-key unique constraint) can be caught *after* that
  transaction has already rolled back, and a **second, independent**
  transaction can then read the now-committed winning row. An AOP
  `@Transactional` method cannot do this cleanly: once a `
  DataIntegrityViolationException` is thrown inside a real database
  transaction (especially on PostgreSQL, which aborts the entire
  transaction block on any failed statement), no further queries can run
  in that same transaction — so resolving the race genuinely requires a
  fresh one, and `TransactionTemplate.execute(...)` called a second time
  provides exactly that without needing self-invocation tricks or a
  second `@Transactional`-annotated bean.
- **Two duplicate-prevention layers, deliberately not merged.** Phase 4's
  `DUPLICATE_ACTION` check (time-window based, re-run fresh every call)
  and the idempotency-key unique constraint (exact, ACID) answer
  different questions - "should this run again" (a policy question) vs
  "did two requests just both try to do the *exact same* attempt right
  now" (a data-integrity question). Merging them would either weaken the
  exact-collision guarantee to a time window, or push data-integrity
  concerns into the policy layer, which is exactly the kind of "policy
  logic inside the execution service" the Phase 7 spec explicitly warns
  against.
- **`amount`/`provider`/`provider_reference` are point-in-time facts, not
  joins.** `RecoveryAttempt.amount` is copied from the transaction at
  attempt-creation time rather than only read via the `transaction`
  relationship, so the persisted attempt remains an accurate historical
  record even if the parent transaction were ever edited later (nothing
  in this codebase does that today, but the audit trail's whole design
  philosophy is point-in-time facts, not mutable joins - consistent with
  how `RevenueRisk`/`AuditLog` already behave).
- **This service does not decide financial safety and does not call any
  AI provider directly.** It has no dependency on `AIRecoveryProvider` -
  only on `RecoveryAgentService`, which already encapsulates that. This
  keeps the "AI cannot bypass policy" guarantee structural at this layer
  too, not just at the agent/policy layer.

## Failure-Recovery Demo (Phase 8)

`backend/src/main/java/com/recoverai/demo/RecoveryDemoService.java` adds a
read/aggregation layer over Phases 3-7 for the 5 fixed named demo
transactions - see
[README.md § Failure-Recovery Demo](../README.md#failure-recovery-demo-phase-8)
for the scenario table and API examples. Architecturally relevant points
not covered there:

- **No new decision logic, structurally enforced.** `RecoveryDemoService`
  declares no field of type `PaymentGateway` or `RecoveryPolicyService` -
  `RecoveryDemoSafetyTest` proves this by reflection over
  `getDeclaredFields()`, the exact same pattern
  `RecoveryPipelineIsolationTest` already established for Phase 6/7. It is
  therefore structurally impossible for the demo layer to call the payment
  gateway directly or bypass the policy engine, not merely unlikely by
  convention.
- **Reuses `RecoveryExecutionService.execute()` verbatim, not a parallel
  path.** The demo does not re-derive AI recommendations or policy
  decisions itself - it calls the same `execute(transactionId)` method
  Phase 7's own controller calls, and reads the same response shape. This
  means any future change to Phase 7's behavior (a new policy check, a new
  gateway) is reflected in the demo automatically, with zero Phase 8 code
  changes, and rules out the two logic paths ever silently diverging.
- **`gatewayCalls` is derived, never counted via a wrapper.** Unlike
  `RecoveryPipelineIsolationTest`/`RecoveryExecutionConcurrencyTest`
  (which wrap `PaymentGateway` in a counting decorator because they are
  specifically testing gateway-invocation counts), `RecoveryDemoService`
  has no access to the gateway to wrap. Instead, `RecoveryDemoSummaryResponse.
  gatewayCalls` is derived purely from `RecoveryExecutionResponse` fields
  already returned by Phase 7: a gateway call happened on this run exactly
  when `provider != null && !duplicate` (a provider is attached only once
  the reservation/idempotency step is reached, and `duplicate=true` means
  this response replays a pre-existing attempt rather than a fresh call).
- **`safetyExplanation` composes from real fields, never invents new
  claims.** `buildSafetyExplanation()` is a `switch` over the real
  `PolicyDecision` plus the real `executionNote`/`policyReason` already
  present on the Phase 7 response - it never asserts a fact the underlying
  services didn't already report.
- **Audit timeline reflects real rows only, seed-authored and live alike.**
  `RevenueRiskService` (Phase 3) does not write to `AuditLog` - it never
  has, in any phase - so there is no *live* `RISK_ANALYZED` step. The
  demo's `auditTimeline` is `AuditLogRepository.
  findByTransactionIdOrderByTimestampAsc(id)` verbatim, so for a
  transaction seeded with prior history it legitimately includes the
  seeder's own historical `RISK_DETECTED`/`RECOVERY_ATTEMPT_RECORDED` rows
  (`actor=SEED_SCRIPT`) ahead of the live `RECOVERY_POLICY_EVALUATED`/
  `RECOVERY_AI_RECOMMENDATION`/`RECOVERY_EXECUTION_*` rows this call's own
  pipeline run produces - confirmed against a live, freshly seeded instance
  during the deployment-phase smoke test, not assumed. (Note the live
  order is `RECOVERY_POLICY_EVALUATED` before `RECOVERY_AI_RECOMMENDATION`,
  not the reverse: `RecoveryAgentService.evaluateTransaction()` calls
  `RecoveryPolicyService.evaluate()` - which writes its own row - before
  it writes its own `RECOVERY_AI_RECOMMENDATION` row.) The Phase 8 spec's
  example diagram is illustrative, and the literal instruction "do not
  fabricate audit events" takes precedence over matching it exactly.
- **Repeatability comes from Phase 4/7 behavior already being idempotent-
  safe, not from new demo-layer state.** `RecoveryDemoService` holds no
  mutable state of its own between calls; running the demo twice is safe
  purely because `RevenueRiskService.analyzeTransaction` is an upsert and
  `RecoveryExecutionService.execute` is already re-blocked by Phase 4's
  `DUPLICATE_ACTION` check on a sequential repeat (see
  [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7)
  above). No new locking, caching, or reset mechanism was needed or added.
- **Frontend types intentionally mirror, not import, the backend DTOs.**
  `frontend/src/types/demo.ts` is a hand-written TypeScript projection of
  `RecoveryDemoScenarioResponse`/`RecoveryDemoSummaryResponse` - there is
  no shared-schema code generation step in this project, matching how the
  rest of the frontend already talks to the backend (plain `axios` calls
  typed by hand).

## Audit, Compliance & Production Hardening (Phase 10)

A hardening pass over the existing architecture above - no new component
changes the AI -> Policy -> Execution -> Audit boundary; three new
cross-cutting components were added, all in `com.recoverai.config`:

- **`GlobalExceptionHandler`** (`@RestControllerAdvice`) - a safety net for
  exceptions no controller-local `@ExceptionHandler` already catches.
  Spring always prefers the more specific handler, so every existing
  local handler (`TransactionNotFoundException` -> 404, etc.) is
  unaffected. Normalizes a malformed path parameter to this API's usual
  `{"error": "..."}` shape and guarantees any unexpected exception is
  logged server-side while the client sees only a generic, safe message.
- **`SecurityHeadersFilter`** (`OncePerRequestFilter`) - adds
  `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
  `Permissions-Policy`, `Cache-Control: no-store`, and
  `Strict-Transport-Security` to every response.
- **`RateLimitFilter`** (`OncePerRequestFilter`, `@Profile("!test")`) - an
  in-memory, per-client, fixed-window limiter guarding
  `/api/recovery-agent/evaluate*`, `/api/revenue-risk/analyze-all`, and
  `/api/recovery/{id}/execute`. Configured via `RateLimitProperties`
  (`recoverai.rate-limit.*` / `RATE_LIMIT_*` env vars). Deliberately
  per-instance in-memory rather than Redis-backed - correct for this
  single-instance deployment, explicitly documented as insufficient for a
  real multi-instance one (see README § Rate limiting). Disabled in the
  `test` profile (mirrors `DemoSeedRunner`'s existing pattern) so it can
  never affect existing test request volume; covered instead by a
  dedicated unit test that instantiates and drives it directly.

One data-minimization change: `TransactionDetailResponse.customerEmail` is
now partially masked in `TransactionDetailResponse.from()` rather than
returned raw - the only DTO field this phase changed.

See [README.md § Audit, Compliance & Production Hardening](../README.md#audit-compliance--production-hardening)
for the full review (AI safety, payment safety, idempotency, audit trail,
PII, CORS, actuator, database, logging, dependencies) and known
limitations - most significantly, **no authentication exists on any
endpoint in this codebase**, a deliberate scope boundary for this phase
(not a hardening gap that was missed), documented there as the top
recommendation before any real-payments deployment.

## Interactive Recovery Console (Phase 11)

The `/demo/recovery` page's data layer was reorganized around a real,
typed API client rather than one bundled backend call:

- **`frontend/src/lib/api.ts`** - every backend call goes through this
  one typed `api.*` object (`analyzeRisk`, `getAiRecommendation`,
  `evaluatePolicy`, `executeRecovery`, `auditTimeline`, `transaction`,
  `riskMetrics`, ...) built on a shared `axios` instance reading
  `VITE_API_BASE_URL`. `toApiError()` normalizes any failure (network,
  timeout, 4xx/5xx) into a safe `{status, message, retryAfterSeconds,
  isLikelyColdStart}` shape - the only place HTTP-error interpretation
  happens, so no component reaches into raw Axios internals.
- **`frontend/src/hooks/useAsyncAction.ts`** - a small hook wrapping one
  backend call with `loading`/`error` state, used by every button in the
  new console.
- **`frontend/src/components/ScenarioOperations.tsx`** - replaces the old
  static `DetailPanel`. Holds one `OperationalState` object per selected
  scenario (reset on selection change) that starts seeded from the
  already-known `RecoveryDemoScenario` fields (from `GET /api/demo/recovery`,
  unchanged) and is progressively replaced by real, explicitly-requested
  fresher data (`RiskAnalysis`, `AgentEvaluation`, `PolicyDecisionResult`,
  `ExecutionResult`, audit entries) as the user clicks each action. The
  guided "Run demo" flow is implemented as a plain sequential `async`
  function over the same `api.*` calls the standalone buttons use - no
  separate/duplicated pipeline logic.
- **`AuditController`** (backend, `GET /api/audit/{transactionId}`) - the
  one new backend endpoint this phase added. A pure read
  (`AuditLogRepository.findByTransactionIdOrderByTimestampAsc`, mapped
  through the existing `AuditTimelineEntryResponse`) - deliberately
  separate from `GET /api/demo/recovery`'s bundled endpoint, which
  re-runs the real evaluate/execute pipeline as a side effect of being
  viewed (existing Phase 8 behavior, intentionally unchanged) and would
  be an unwanted side effect for a plain "refresh the audit panel" click.

**Deliberate design boundary, unchanged from every earlier phase:** the
frontend still never decides whether an action is authorized. The
guided flow's "ready for execution" state is reached only by reading
`policyDecision.decision === 'ALLOW'` from a real backend response, and
the Execute button calls `POST /api/recovery/{id}/execute` with no
request body - the server derives transaction, amount, currency, action,
attempt number, and idempotency key from persisted state exactly as it
did before this phase, and independently re-blocks a duplicate execute
attempt regardless of what the frontend's button state shows (verified
live - see README § Interactive Recovery Console).

## Sections to be added in later phases

- Batch recovery execution and aggregate "revenue recovered" metrics (explicitly deferred - see the Phase 7 spec's own prohibition on claiming this before confirmed provider payment data exists)
- Provider-confirmed payment detection (a Razorpay webhook) - the only mechanism that could ever make `amountRecovered > 0` reachable in this codebase
- Observability / audit trail design (beyond the `AuditLog` entity itself)
- A full dashboard (Phase 9) - the Phase 8 `/demo/recovery` page is a
  curated 5-scenario walkthrough, not a general-purpose transaction browser
