# RecoverAI

**AI Revenue Recovery Agent** — built for the Razorpay Buildathon, Track 03.

> Detect revenue at risk. Decide the right intervention. Recover it safely.

RecoverAI watches a merchant's transaction stream, flags payments that are at
risk of never being collected (failed charges, abandoned checkouts, failed
subscriptions), uses an AI agent to diagnose *why* and recommend a recovery
action, runs that recommendation through a deterministic safety policy
engine, and only then executes a bounded recovery action — logging every
decision along the way so the whole workflow can be audited.

This README is being built up phase by phase alongside the implementation.
See [Project status](#project-status) for what exists today.

## Table of contents

1. [Problem](#problem)
2. [Solution](#solution)
3. [Why AI is needed](#why-ai-is-needed)
4. [Architecture](#architecture)
5. [Technology stack](#technology-stack)
6. [Project status](#project-status)
7. [Repository layout](#repository-layout)
8. [Setup instructions](#setup-instructions)
9. [Environment variables](#environment-variables)
10. [Running locally](#running-locally)
11. [Testing](#testing)
12. [Dataset](#dataset)
13. [Revenue Risk Engine (Phase 3)](#revenue-risk-engine-phase-3)
14. [Recovery Safety / Policy Engine (Phase 4)](#recovery-safety--policy-engine-phase-4)
15. [AI Recovery Agent (Phase 5)](#ai-recovery-agent-phase-5)
16. [Razorpay Integration / Payment Adapter (Phase 6)](#razorpay-integration--payment-adapter-phase-6)
17. [Recovery Execution Pipeline (Phase 7)](#recovery-execution-pipeline-phase-7)
18. [Failure-Recovery Demo (Phase 8)](#failure-recovery-demo-phase-8)
19. [What is real vs. simulated](#what-is-real-vs-simulated)
20. [Known limitations](#known-limitations)
21. [Buildathon Deployment](#buildathon-deployment)
22. [Audit, Compliance & Production Hardening](#audit-compliance--production-hardening)
23. [Interactive Recovery Console](#interactive-recovery-console-phase-11)

Further sections (AI architecture, safety architecture, Razorpay
integration, dataset, evaluation methodology, metrics, failure handling,
demo flow, future improvements) will be added as those phases land — see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DEMO.md](docs/DEMO.md)
and [docs/API.md](docs/API.md).

## Problem

Payments fail for reasons that have nothing to do with a customer's intent
to pay: an expired card, a bank's risk rule, a temporary network blip, an
insufficient-funds moment that resolves itself the next day. Merchants
routinely leave this revenue on the table because reacting to every failed
payment individually is operationally expensive, and blunt "retry
everything" automation risks annoying customers, tripping fraud rules, or
retrying charges that were never going to succeed.

## Solution

RecoverAI turns each at-risk transaction into a small, auditable decision
pipeline: score the risk → ask an AI agent to diagnose the failure and
propose one bounded action → validate that action against hard safety
rules → execute it through an isolated adapter → record the outcome. The
system is explicit about what it recovered, what it couldn't, and why —
so the dashboard is a source of truth for revenue recovery, not a demo
gimmick.

## Why AI is needed

Failure reasons and customer histories are heterogeneous enough that a
fixed if/else tree either over-retries (annoying customers, wasting gateway
attempts) or under-retries (leaving recoverable revenue unclaimed). An LLM
agent can weigh qualitative context — failure text, recency, history — and
produce a *reasoned, explainable* recommendation. Critically, the AI only
recommends: it never has direct access to payment APIs. A deterministic
safety policy engine is the actual gate on any money-moving action. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) once Phase 5–7 land for the
full design.

## Architecture

```
Transaction data
   -> Revenue-at-risk detection (deterministic scoring)
   -> AI diagnosis + recommendation (structured output, no tool access)
   -> Safety/policy validation (deterministic, hard rules)
   -> Recovery action execution (Razorpay Test Mode or simulation adapter)
   -> Result
   -> Audit trail
   -> Revenue recovery metrics
```

Full component-level architecture will be documented in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) as each layer is implemented.

## Technology stack

**Frontend:** React, Vite, TypeScript, Tailwind CSS, Recharts, React Router, Axios

**Backend:** Java 17, Spring Boot, Maven, Spring Web, Spring Data JPA,
PostgreSQL, Flyway, Bean Validation, Spring Boot Actuator

**AI:** Provider-abstracted; a mock deterministic provider for local dev/tests,
Anthropic Claude for real inference (Phase 5)

**Payments:** Razorpay Test Mode APIs (Payment Links) where supported,
isolated behind a `PaymentGateway` abstraction with an explicit,
deterministic mock fallback (Phase 6); the orchestration that actually
invokes it is Phase 7

## Project status

Built in phases; each phase is verified (build + tests + manual run) before
moving to the next.

- [x] **Phase 1 — Project foundation.** Backend (Spring Boot, Maven, Java 17)
      and frontend (Vite + React + TS + Tailwind v4) scaffolds, database
      configuration (PostgreSQL primary, H2 fallback for offline dev), CORS,
      health endpoint, Docker Compose for Postgres, env var template.
- [x] **Phase 2 — Domain model + database.** Six JPA entities (`Merchant`,
      `Customer`, `Transaction`, `RevenueRisk`, `RecoveryAttempt`,
      `AuditLog`), seven PostgreSQL-targeted Flyway migrations, Spring Data
      repositories, a deterministic synthetic dataset generator (500
      transactions + 5 named demo transactions), and two minimal read-only
      transaction endpoints. See [Dataset](#dataset) below.
- [x] **Phase 3 — Revenue Risk Engine.** Deterministic `RevenueRiskService`
      producing `amountAtRisk`, `riskScore` (0-100), `recoveryProbability`
      (0.0-1.0, deliberately distinct from riskScore), a `riskLevel`
      classification, structured `factors`, and a concise `reason` for
      every revenue-loss-state transaction; idempotent persistence,
      batch analysis, and aggregate metrics. No AI involved. See
      [Revenue Risk Engine](#revenue-risk-engine-phase-3) below.
- [x] **Phase 4 — Recovery Safety / Policy Engine.** Deterministic
      `RecoveryPolicyService` evaluating a proposed `RecoveryAction`
      against a transaction's authoritative database state (never a
      client-supplied fact) and returning `ALLOW`/`BLOCK`/`ESCALATE`/`STOP`
      with a structured, explainable `policyChecks` list. Enforces retry
      limits, an autonomous amount ceiling, duplicate-action prevention,
      repeated-failure/total-action stopping, and already-resolved/
      escalated/stopped protection; writes a deduplicated audit trail. No
      AI, no Razorpay, no action execution. See
      [Recovery Safety / Policy Engine](#recovery-safety--policy-engine-phase-4)
      below.
- [x] **Phase 5 — AI Recovery Agent.** `RecoveryAgentService` builds an
      authoritative `RecoveryAgentContext` from the database (never from
      client input), asks a pluggable `AIRecoveryProvider` for a
      structured `RecoveryRecommendation`, and hands the recommended
      action to Phase 4's `RecoveryPolicyService` exactly like any other
      caller — the AI only recommends, the policy engine still decides.
      Default provider is `mock` (deterministic, offline, no API key); an
      untested-but-real `AnthropicAIRecoveryProvider` scaffold exists for
      `recoverai.ai.provider=anthropic`. No execution, no Razorpay. See
      [AI Recovery Agent](#ai-recovery-agent-phase-5) below.
- [x] **Phase 6 — Razorpay Integration / Payment Adapter.** `PaymentGateway`
      abstraction (`MockPaymentGateway` default, `RazorpayPaymentGateway`
      real-but-untested) executing an already-authorized `RETRY_PAYMENT`/
      `CREATE_PAYMENT_LINK` operation. Database-enforced idempotency
      (migration V9), structured `PaymentExecutionResult` (never claims
      confirmed recovery), fails closed on every provider error. No public
      execution endpoint, no autonomous orchestration — proven separate
      from the AI/policy layers by an explicit structural + behavioral
      test. See
      [Razorpay Integration / Payment Adapter](#razorpay-integration--payment-adapter-phase-6)
      below.
- [x] **Phase 7 — Recovery Execution Pipeline.** `RecoveryExecutionService`
      is the first component that actually connects AI recommendation →
      policy authorization → payment execution → persisted
      `RecoveryAttempt` → transaction state → audit, end to end. Executes
      only on a fresh `ALLOW`; database-backed idempotency (a pre-check
      plus the Phase 6 unique constraint) makes duplicate/concurrent
      requests produce at most one provider call; `amountRecovered`-based
      transaction-state transition is implemented honestly (link creation
      never marks a transaction `RECOVERED`). One new endpoint,
      `POST /api/recovery/{transactionId}/execute` — no request body, no
      client-controlled amount/action. See
      [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7)
      below.
- [x] **Phase 8 — Failure-Recovery Demo Scenario.** `RecoveryDemoService`
      is a read/aggregation layer over the real Phase 3-7 pipeline — no
      risk/AI/policy/payment decision logic of its own — that runs the
      five fixed named demo transactions through `RevenueRiskService` and
      `RecoveryExecutionService` and shapes the results (plus the real
      persisted audit trail) for presentation. Two new endpoints
      (`GET /api/demo/recovery`, `GET /api/demo/recovery/{externalTransactionId}`)
      and a new frontend page (`/demo/recovery`) turn that into a
      hackathon-pitch-ready dashboard: KPIs, a visual pipeline diagram,
      per-scenario cards, and a full decision/audit detail panel. No fake
      recovered revenue anywhere — `confirmedAmountRecovered` is summed
      only from real `amountRecovered` figures, which stay `0.00` with
      today's mock/Razorpay gateways. See
      [Failure-Recovery Demo](#failure-recovery-demo-phase-8) below.
- [x] **Phase 9 — Production Deployment.** The application above deployed
      and verified live (Neon PostgreSQL, Render backend, Vercel
      frontend), no product behavior changed. See
      [Live deployment](#12-live-deployment-phase-9) below. *(Supersedes
      this checklist's original "Phase 9 — Dashboard" placeholder — no
      general-purpose transaction dashboard exists yet; that remains a
      future phase.)*
- [x] **Phase 10 — Audit, Compliance & Production Hardening.** A security/
      compliance review and hardening pass over the deployed system —
      global error handling, HTTP security headers, per-client rate
      limiting, PII masking, dependency cleanup — no safety boundary
      weakened. See
      [Audit, Compliance & Production Hardening](#audit-compliance--production-hardening)
      below.
- [x] **Phase 11 — Interactive Recovery Console.** The `/demo/recovery`
      frontend upgraded from a static snapshot into a real operational
      console — Analyze Risk, Get AI Recommendation, Evaluate Policy, and
      Execute Recovery are now individually clickable actions calling the
      real backend, plus a guided step-by-step "Run demo" flow and a live
      audit-timeline refresh. See
      [Interactive Recovery Console](#interactive-recovery-console-phase-11)
      below. *(This checklist's original "Phase 11 — Testing + hardening"
      placeholder is covered by Phase 10 above.)*
- [ ] A general-purpose transaction dashboard (any transaction, not just
      the 5 curated demo scenarios) — still a future phase.
- [ ] Batch execution and a real, measured "₹X recovered" figure across
      many transactions — still a future phase, pending a provider-
      confirmation mechanism (e.g. a Razorpay webhook).

## Repository layout

```
recoverai/
├── backend/                Spring Boot API (Java 17, Maven)
│   ├── src/main/java/com/recoverai/
│   │   ├── config/         Cross-cutting configuration (CORS, RevenueRiskProperties, RecoveryPolicyProperties, etc.)
│   │   ├── controller/     REST controllers
│   │   ├── domain/         JPA entities and enums
│   │   ├── dto/            Read-only HTTP response/request projections
│   │   ├── repository/     Spring Data JPA repositories
│   │   ├── risk/           Phase 3 - deterministic revenue risk engine
│   │   ├── policy/         Phase 4 - deterministic recovery safety/policy engine
│   │   ├── agent/          Phase 5 - AI recovery agent (context, provider abstraction, orchestration)
│   │   ├── payment/        Phase 6 - PaymentGateway abstraction (mock + Razorpay), idempotency
│   │   ├── execution/      Phase 7 - Recovery Execution Pipeline (orchestration)
│   │   ├── demo/           Phase 8 - read/aggregation demo layer over Phases 3-7 (no new decision logic)
│   │   └── seed/           Deterministic synthetic dataset generator
│   └── src/main/resources/
│       ├── application.yml, application-local.yml
│       └── db/migration/   Flyway migrations (V1-V10)
├── frontend/                Vite + React + TypeScript dashboard
│   └── src/
│       ├── pages/           Home, RecoveryDemoPage (Phase 8, route: /demo/recovery)
│       ├── components/      Shared UI (Badge)
│       └── types/           TS types mirroring backend DTOs
├── docs/                    Architecture, demo script, API reference
├── scripts/                 Dev convenience scripts
├── docker-compose.yml       PostgreSQL for local development
├── .env.example             Environment variable template
└── README.md
```

## Setup instructions

### Prerequisites

- Java 17 (Temurin or equivalent)
- Maven 3.9+
- Node.js 20+ and npm
- PostgreSQL 16, **or** Docker + Docker Compose to run Postgres in a container

> This development machine has neither Docker nor a local PostgreSQL
> install available. Two things fill that gap, for different purposes:
>
> - The backend ships a documented `local` Spring profile
>   (`SPRING_PROFILES_ACTIVE=local`) that runs against an embedded H2
>   database in PostgreSQL-compatibility mode, for quick smoke testing.
>   Flyway is disabled under this profile (Flyway migrations are
>   PostgreSQL-specific) — Hibernate generates the schema instead.
> - The backend's test suite includes `PostgresMigrationIntegrationTest`,
>   which starts a real, JVM-managed PostgreSQL instance
>   (`io.zonky.test:embedded-postgres`, no Docker required) and runs the
>   actual Flyway migrations and `ddl-auto: validate` against it. This is
>   what genuinely proves the migrations and entity mappings are correct
>   for PostgreSQL — see [Testing](#testing).
>
> Neither replaces having a persistent PostgreSQL instance for actually
> running the app day to day — install Docker Desktop or PostgreSQL 16
> locally for that (`docker compose up -d postgres`).

### 1. Clone and configure environment

```bash
cp .env.example .env
# edit .env if you need non-default ports/credentials
```

### 2. Start PostgreSQL

```bash
docker compose up -d postgres
```

(Or point `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` in `.env` at an existing
local PostgreSQL instance.)

### 3. Install frontend dependencies

```bash
cd frontend
npm install
```

## Environment variables

See [.env.example](.env.example) for the full, documented list. Highlights:

| Variable | Purpose | Required |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection | Yes (or use `local` profile) |
| `AI_PROVIDER` | `mock` (default, offline) or `anthropic` | No |
| `ANTHROPIC_API_KEY` | Real Claude inference | Only if `AI_PROVIDER=anthropic` |
| `RAZORPAY_MODE` | `simulation` (default) or `test` | No |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Razorpay Test Mode | Only if `RAZORPAY_MODE=test` |
| `VITE_API_BASE_URL` | Frontend → backend base URL | No (defaults to `http://localhost:8080`) |

Secrets are never read by the frontend bundle except variables explicitly
prefixed `VITE_`, and none of those are secret values.

## Running locally

**Backend** (defaults to the PostgreSQL profile; requires Postgres running):

```bash
# from repo root, loads .env and starts Spring Boot
./scripts/run-backend.sh        # macOS/Linux/Git Bash
./scripts/run-backend.ps1       # Windows PowerShell
```

**Backend, without Postgres/Docker** (H2 fallback, for quick smoke testing only):

```bash
cd backend
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

**Frontend:**

```bash
cd frontend
npm run dev
```

Then open `http://localhost:5173`. The landing page calls
`GET /api/health` on the backend and shows connection status — useful as a
quick check that both halves of the stack are wired up correctly.

## Testing

**Backend:**

```bash
cd backend
mvn test
```

`mvn test` never requires a pre-existing PostgreSQL, Docker, Razorpay, or
LLM connection — everything it needs, it starts itself:

| Test class | Database | What it verifies |
|---|---|---|
| `EntityPersistenceTest` | H2 (in-memory, `test` profile) | Entity mappings, relationships, constraints (unique, required fields, enum persistence, `BigDecimal` precision) |
| `DemoDataSeederTest` | H2 (in-memory, `test` profile) | The synthetic dataset generator produces the expected volume/distribution |
| `TransactionControllerTest` | H2 (in-memory, `test` profile) | `GET /api/transactions` and `GET /api/transactions/{id}` over real HTTP (MockMvc) |
| `RevenueRiskServiceTest` | H2 (in-memory, `test` profile) | The risk formula itself: amount/history/attempt/failure-category weighting, edge cases (no history, unknown category, resolved statuses), risk-score-vs-recovery-probability independence, idempotent persistence, determinism |
| `RevenueRiskBatchTest` | H2 (in-memory, `test` profile) | Batch analysis over the full 500-transaction seed dataset: aggregate metrics are internally consistent, resolved transactions never count as at-risk, each named demo transaction lands in its intended risk profile |
| `RevenueRiskControllerTest` | H2 (in-memory, `test` profile) | The five `/api/revenue-risk/*` endpoints over real HTTP (MockMvc) |
| `RecoveryPolicyServiceTest` | H2 (in-memory, `test` profile) | Every ALLOW/BLOCK/ESCALATE/STOP branch: retry limit, total-action cap, autonomous amount ceiling (incl. exact-boundary), duplicate-action window (incl. outside-window), already-resolved/escalated/stopped protection, explicit ESCALATE/STOP actions, CRITICAL-risk-forces-escalation, missing-risk-row safety, determinism, and the audit-dedup behavior |
| `RecoveryPolicyDemoScenariosTest` | H2 (in-memory, `test` profile) | Each of the 5 named demo transactions evaluated with `RETRY_PAYMENT` lands on the decision it is meant to demonstrate |
| `RecoveryPolicyControllerTest` | H2 (in-memory, `test` profile) | `POST /api/recovery-policy/evaluate/{id}` over real HTTP (MockMvc) — 200 with full response shape, 404 unknown transaction, 400 unknown/missing action |
| `RecoveryAgentServiceTest` | H2 (in-memory, `test` profile) | Full context→AI→policy pipeline: all 16 scenarios from the Phase 5 spec — easy/high-value/repeated-failure/stopped/recovered/pending/abandoned/critical-risk transactions, malformed AI output, provider failure, invalid (null) action, confidence boundaries 0 and 1 (accepted) and out-of-range (rejected), zero and negative `expectedRecoveryValue`, determinism, and the two mandatory policy-override tests (AI recommends `RETRY_PAYMENT`, policy overrides to `STOP`/`ESCALATE`, no `RecoveryAttempt` row is ever written) |
| `RecoveryAgentDemoScenariosTest` | H2 (in-memory, `test` profile) | Full pipeline against each of the 5 named demo transactions, after a real Phase 3 risk analysis pass |
| `RecoveryAgentBatchTest` | H2 (in-memory, `test` profile) | `evaluateAll()` over the full seed dataset - aggregate recommendation/decision counts sum correctly, average confidence in [0,1], deterministic across repeated runs |
| `RecoveryAgentControllerTest` | H2 (in-memory, `test` profile) | `POST /api/recovery-agent/evaluate/{id}` and `/evaluate-all` over real HTTP (MockMvc) |
| `MockPaymentGatewayTest` | none (plain unit test) | Success, deterministic decline/timeout markers, deterministic repeatability, amount/currency/action validation, `amountRecovered` is always zero (never conflated with confirmed recovery) |
| `RazorpayPaymentGatewayTest` | none (plain unit test) | Missing-credentials and invalid-request paths fail closed as structured results (never an exception); a network-layer failure (connection refused, no real network access) never leaks the configured API key/secret |
| `PaymentGatewayIdempotencyTest` | H2 (in-memory, `test` profile) | `recovery_attempts.idempotency_key`'s unique constraint (migration V9) actually rejects a duplicate-key insert at the database level; distinct keys and legacy `NULL` keys are unaffected; the key generator is deterministic |
| `PaymentAuditEventsTest` | none (plain unit test) | `PAYMENT_PROVIDER_EXECUTION` audit-event shape for both success and failure, and that metadata never contains secret-like fields |
| `RecoveryPipelineIsolationTest` | H2 (in-memory, `test` profile) | The mandatory Phase 6 safety test: `RecoveryAgentService`/`RecoveryPolicyService` have no field of type `PaymentGateway` (reflection), and a counting gateway wrapper proves zero invocations when policy returns `STOP`/`ESCALATE`, exactly one when it returns `ALLOW` |
| `RecoveryExecutionServiceTest` | H2 (in-memory, `test` profile) | The full Phase 7 pipeline: policy-boundary gateway-call counts, `RecoveryAttempt` lifecycle (created only on `ALLOW` + a payment action), attempt numbering derived from persisted history, deterministic idempotency keys, Phase 4's `DUPLICATE_ACTION` check correctly stopping a repeated sequential call, provider result handling (success, decline, timeout, provider-unavailable, malformed response, amount/transaction mismatch), the `amountRecovered`-gated transaction-state mapping (including the not-yet-reachable "confirmed payment → RECOVERED" branch, proven correct in isolation), exact-amount preservation, and the full execution audit trail |
| `RecoveryExecutionConcurrencyTest` | H2 (in-memory, `test` profile) | Two real threads calling `execute()` for the same transaction simultaneously produce exactly one provider call and one `RecoveryAttempt` row - the database unique constraint, not application logic, decides the race |
| `RecoveryExecutionControllerTest` | H2 (in-memory, `test` profile) | `POST /api/recovery/{id}/execute` over real HTTP (MockMvc) - no request body, 404 unknown transaction, and confirms no raw `/api/payments/execute` endpoint exists |
| `RecoveryExecutionDemoScenariosTest` | H2 (in-memory, `test` profile) | The full pipeline against each of the 5 named demo transactions, matching the Phase 7 spec's expected outcomes exactly |
| `RecoveryDemoServiceTest` | H2 (in-memory, `test` profile) | Phase 8's `RecoveryDemoService`: all 5 scenario outcomes, aggregate counts, per-scenario audit timeline never empty, `confirmedAmountRecovered` always zero, running the demo twice adds no additional `RecoveryAttempt` rows and stays blocked by Phase 4's duplicate-action check, unknown scenario id throws, `runOne` matches the corresponding `runAll` entry |
| `RecoveryDemoControllerTest` | H2 (in-memory, `test` profile) | `GET /api/demo/recovery` and `GET /api/demo/recovery/{externalTransactionId}` over real HTTP (MockMvc) — 200 with full aggregate/scenario shape, 404 for an unknown scenario id, response body never contains a secret-like field |
| `RecoveryDemoSafetyTest` | H2 (in-memory, `test` profile) | The mandatory Phase 8 safety guarantees: `RecoveryDemoService` has no field of type `PaymentGateway` or `RecoveryPolicyService` (reflection — it structurally cannot bypass either), the high-value/repeated-failure/already-recovered scenarios add zero new `RecoveryAttempt` rows, the easy-recovery scenario adds exactly one (`provider=mock`), re-running the demo keeps duplicate-action protection active, `executionStatus=SUCCESS` alone never transitions a transaction to `RECOVERED`, `amountRecovered`/`confirmedAmountRecovered` are always zero and are never derived from `potentialRecoveryValue`/`amountAtRisk` |
| `PostgresMigrationIntegrationTest` | Real PostgreSQL, JVM-managed (`io.zonky.test:embedded-postgres`) | Flyway migrations V1-V10 apply cleanly to actual PostgreSQL; Hibernate `ddl-auto: validate` confirms every entity mapping matches (including the Phase 3 `risk_level`/`factors` columns, the Phase 6 `idempotency_key` column, and the Phase 7 `amount`/`provider`/`provider_reference` columns); the seeder works end-to-end including the `jsonb` columns; both the Phase 6 and Phase 7 unique-constraint tests are re-verified here against real PostgreSQL, not just H2. Phases 4 and 5 introduced no schema changes. |

The last one is slower (it downloads/starts/stops a real Postgres binary,
~25-70s) but is the one that actually exercises PostgreSQL rather than
H2's looser compatibility mode — it caught two real bugs during
development: a `CHECK` constraint that H2 didn't enforce the same way
(Phase 2), and would have caught a `jsonb`-vs-H2-`JSON` type mismatch had
one existed for the Phase 3 `factors` column.

**Frontend:**

```bash
cd frontend
npm run build     # type-checks (tsc -b) and produces a production bundle
```

(A dedicated frontend test runner will be added if/when component-level
tests are introduced.)

## Dataset

`DemoDataSeeder` (`backend/src/main/java/com/recoverai/seed/DemoDataSeeder.java`)
generates a deterministic, entirely synthetic dataset for one seed
merchant ("Nimbus Retail"): 120 customers with varied payment history
(strong/mixed/weak), and 500 transactions — 495 generated in bulk plus 5
fixed, named demo transactions (`demo-easy-recovery`,
`demo-retry-escalation`, `demo-high-value`, `demo-repeated-failure`,
`demo-successful-recovery`) intended for later phases' demo walkthroughs.

**Determinism:** a single `java.util.Random` seeded with a fixed constant
is consumed in a fixed order, so the categorical shape of the dataset
(status mix, failure categories, customer history profiles, which
transactions get risk/recovery records) is reproducible across runs.
`createdAt` timestamps are anchored to "now" minus a deterministic offset,
so they shift with the run date but always land within the same rolling
window — appropriate for future dashboard "last N days" views.

**This is not the risk-scoring engine or the safety policy engine.** The
`RevenueRisk` and `RecoveryAttempt` rows the seeder creates are
hand-authored historical seed facts ("here is what already happened to
this transaction, for demo purposes"), clearly labeled as such in their
`reason` fields and in code comments — not the live output of Phase 3's
`RevenueRiskService` or Phase 4's `RecoveryPolicyService` (both of which
re-derive their own answers from this same seed data when actually
invoked, rather than trusting these seeded values). Failure
categories (`TEMPORARY_FAILURE`, `INSUFFICIENT_FUNDS`, `BANK_DECLINED`,
`NETWORK_ERROR`, `AUTHENTICATION_FAILURE`, `LIMIT_EXCEEDED`, `UNKNOWN`)
are an application-invented taxonomy, not real Razorpay failure codes.

The seeder is not yet wired to an HTTP endpoint (`POST /api/demo/seed` is
planned for a later phase); it is called directly from tests today. See
`DemoDataSeederTest` and `PostgresMigrationIntegrationTest` for the actual
generated counts, logged and asserted from the database — never
hardcoded.

`seed()` now wipes any previously seeded data before regenerating (delete,
in FK-safe order, then recreate), so it's safe to call more than once —
both for repeated test runs and for a future `POST /api/demo/reset` +
reseed workflow.

## Revenue Risk Engine (Phase 3)

`RevenueRiskService` (`backend/src/main/java/com/recoverai/risk/`) turns a
transaction, its customer's payment history, and its recovery-attempt
history into `amountAtRisk`, `riskScore`, `recoveryProbability`, a
`riskLevel` classification, structured `factors`, and a concise `reason`.
It is entirely deterministic — no AI/LLM call is made anywhere in this
phase; the same input always produces the same output (see
`RevenueRiskServiceTest`'s determinism test). All weights live in
`RevenueRiskProperties` (bound from `application.yml`'s `recoverai.risk.*`
section) and are **application-invented illustrative synthetic values for
this prototype, not a statistically fitted or externally validated
model** — this is explicitly a baseline, not a claim of ML accuracy.

**Risk score vs. recovery probability — deliberately distinct metrics:**

- `riskScore` (0-100, Phase 2's original `RevenueRisk` scale) answers *"how
  much/how urgent is the exposure?"* — a weighted blend of transaction
  amount (weighted heaviest, 0.65), failure-category severity (0.18),
  customer-history weakness (0.05), and prior-attempt urgency (0.12).
- `recoveryProbability` (0.0-1.0) answers *"how likely is recovery if
  attempted?"* — a failure-category baseline (e.g. `TEMPORARY_FAILURE`
  0.80, `BANK_DECLINED` 0.35), adjusted by customer history (±0.15) and
  penalized per prior attempt (-0.15 each), then multiplied by 0.5 if the
  transaction has already left the automated retry path (`ESCALATED` /
  `STOPPED`).

These can and do move independently: a ₹47,500 `TEMPORARY_FAILURE` from a
strong-history customer scores `riskScore=68.19` (HIGH — the exposure is
large) **and** `recoveryProbability=0.6824` (likely recoverable) at the
same time — see `demo-high-value` below. `RevenueRiskServiceTest.
highAmountAtRisk_doesNotImplyLowRecoveryProbability` asserts this
explicitly.

**Amount at risk:** `FAILED`/`PENDING`/`ABANDONED`/`ESCALATED`/`STOPPED`
transactions carry `amountAtRisk = transaction.amount` (real, unresolved
exposure). `SUCCESS` and `RECOVERED` transactions are zeroed out
(`amountAtRisk = 0`, `riskScore = 0`, `recoveryProbability = 1.0`) —
already-collected revenue is never counted as at-risk. Batch analysis also
corrects any stale risk row left over from before a transaction resolved
(the Phase 2 seed data intentionally gives `RECOVERED` transactions a
seed-heuristic risk row representing "what it looked like before recovery
succeeded" — the batch sweep zeroes those out too).

**Idempotency:** `revenue_risks.transaction_id` is unique (migration V8);
re-analyzing a transaction updates its existing row rather than
accumulating duplicates.

**Buildathon alignment:**

```
Revenue event -> Revenue-at-risk detection -> Quantification
   -> Recovery-opportunity estimation -> Structured context
   -> (Phase 5) AI intervention decision
```

This phase is the measurable baseline the eventual "money recovered across
a batch" evaluation builds on.

## Recovery Safety / Policy Engine (Phase 4)

`RecoveryPolicyService` (`backend/src/main/java/com/recoverai/policy/`) is
the authorization boundary between a recovery *recommendation* and an
actually-executed recovery *action*:

```
AI Recommendation (Phase 5)
        |
        v
RecoveryPolicyService  <-- deterministic, no AI, no Razorpay call
        |
        v
  ALLOW / BLOCK / ESCALATE / STOP
        |
        v
Recovery execution (Phase 7)
```

**The AI may recommend an action; only this deterministic service decides
whether it is allowed to run.** It never executes a `RecoveryAction`
itself — `POST /api/recovery-policy/evaluate/{transactionId}` only
evaluates and returns a decision, and never calls Razorpay, never writes a
`RecoveryAttempt` row, and never calls an AI/LLM provider.

**Authoritative inputs only.** A caller supplies a transaction ID and a
proposed `RecoveryAction` — nothing else. Transaction status, amount,
recovery-attempt history, and risk level are all loaded fresh from the
database inside `evaluate()`; a client can never spoof the facts a
decision is based on.

**Checks run in a fixed priority order** and short-circuit at the first
one that determines the outcome (`policyChecks` in the response therefore
only lists checks that were actually relevant):

| # | Check | What it catches |
|---|---|---|
| 1 | `TRANSACTION_STATUS` | `SUCCESS`/`RECOVERED` -> `BLOCK`; `ESCALATED` -> `ESCALATE`; `STOPPED` -> `STOP` (all before any action-specific logic runs) |
| 2 | `ACTION_COMPATIBILITY` | An explicit `ESCALATE`/`STOP` action is honored directly; `RETRY_PAYMENT` against `PENDING`/`ABANDONED` is `BLOCK`ed (nothing has actually failed to retry yet) |
| 3 | `RETRY_LIMIT` | Only for `RETRY_PAYMENT` — prior retry count >= `maxAutomaticRetryAttempts` (default 2) -> `STOP` |
| 4 | `REPEATED_FAILURE` | Total recovery actions of any kind already recorded >= `maxRecoveryActionsPerTransaction` (default 3) -> `STOP` (catches mixed-action exhaustion `RETRY_LIMIT` alone would miss) |
| 5 | `AMOUNT_LIMIT` | Amount > `maxAutonomousRecoveryAmount` (default ₹25,000) -> `ESCALATE`, `requiresHumanApproval=true` |
| 6 | `DUPLICATE_ACTION` | The same action already `SUCCESS`/`PENDING` within `duplicateActionWindowHours` (default 24h) -> `BLOCK` |
| 7 | `RISK_FLAGS` | Phase 3 `riskLevel == CRITICAL` -> `ESCALATE` (a missing risk row is treated as "no signal", not an error) |

If every applicable check passes, the decision is `ALLOW`.

**High value does not mean high risk.** `AMOUNT_LIMIT` is independent of
Phase 3's `riskScore`/`recoveryProbability` by design — a ₹47,500
`INSUFFICIENT_FUNDS` failure from a strong-history customer can score
`riskScore=68.19` and `recoveryProbability=0.6824` (a good recovery bet)
and still be `ESCALATE`d purely on amount, because the safety boundary and
the risk model answer different questions. Conversely, `RISK_FLAGS` shows
the reverse relationship: a `CRITICAL` risk level can force escalation,
but a *good* risk level never by itself authorizes an `ALLOW` — every
other check must still pass.

**All thresholds are configurable**, not hardcoded — see
`RecoveryPolicyProperties`, bound from `application.yml`'s
`recoverai.policy.*`.

**Audit trail, deduplicated.** Every decision writes a
`RECOVERY_POLICY_EVALUATED` `AuditLog` row (`actor=POLICY_ENGINE`,
`decision`, `reason`, `metadata.action`) — except a repeated evaluation of
the same transaction+action that reaches the *same* decision as the most
recent recorded one, which is skipped. This endpoint is expected to be
callable repeatedly (e.g. by a future dashboard) without side effects, so
this keeps the audit trail one entry per real state transition instead of
one entry per poll.

**Demo scenarios** (`RecoveryPolicyDemoScenariosTest`, `RETRY_PAYMENT` proposed against each):

| Transaction | Decision | Why |
|---|---|---|
| `demo-easy-recovery` | `ALLOW` | Fresh `FAILED`, no prior attempts, amount well under the autonomous limit |
| `demo-high-value` | `ESCALATE` (`requiresHumanApproval=true`) | ₹47,500 exceeds the ₹25,000 autonomous amount limit — independent of its good recovery probability |
| `demo-retry-escalation` | `ESCALATE` | Already `ESCALATED` — autonomous recovery defers to the pending human review |
| `demo-repeated-failure` | `STOP` | Already `STOPPED` — autonomous recovery remains permanently halted for this transaction |
| `demo-successful-recovery` | `BLOCK` | Already `RECOVERED` — no recovery action is applicable |

## AI Recovery Agent (Phase 5)

`RecoveryAgentService` (`backend/src/main/java/com/recoverai/agent/`) is
the recommendation layer between Phase 3's risk analysis and Phase 4's
authorization boundary:

```
Transaction
    |
    v
Revenue Risk Engine (Phase 3)
    |
    v
RecoveryAgentContext          <-- built from the database only, never from client input
    |
    v
AIRecoveryProvider             <-- pluggable; mock (default) or Anthropic
    |
    v
RecoveryRecommendation         <-- advisory: action, confidence, rationale, expectedRecoveryValue
    |
    v
RecoveryPolicyService (Phase 4) <-- unchanged, authoritative
    |
    v
ALLOW / BLOCK / ESCALATE / STOP  ->  finalAction
```

**The AI recommends; it never authorizes.** `RecoveryAgentService.evaluate()`
hands the AI's recommended action to `RecoveryPolicyService.evaluate()` -
the exact same method any other caller uses - and `finalAction` always
reflects the *policy* decision, not the AI's raw recommendation, whenever
they differ. This is exercised directly: `RecoveryAgentServiceTest`
constructs a transaction with 2 prior failed retries and a stub AI
provider that recommends `RETRY_PAYMENT` regardless of context, and
asserts `finalAction == STOP` and that no `RecoveryAttempt` row is ever
written (this phase never executes anything).

**Provider abstraction, mock by default.** `AIRecoveryProvider` is the
only thing `RecoveryAgentService` depends on; which implementation backs
it is chosen once, in `AIProviderConfig`, from `recoverai.ai.provider`
(default `mock`). `MockAIRecoveryProvider` is deterministic and
offline - the same context always produces the same recommendation, and
every automated test runs against it, so the whole project builds, runs,
and is fully tested without any AI API key. It reasons contextually
(status, recovery probability, prior-attempt count, risk level, amount) -
not a bare "failure category → action" lookup - using its own heuristics,
deliberately distinct from Phase 4's exact thresholds (see "policy
override" above). `AnthropicAIRecoveryProvider` is a real, complete
implementation of the same interface for `recoverai.ai.provider=anthropic`
- see [Known limitations](#known-limitations) for its verification status.

**Structured recommendation, not free text.** `RecoveryRecommendation`
carries `recommendedAction` (the existing `RecoveryAction` enum - never an
arbitrary string), `confidence` (0.0-1.0), `rationale` (one concise
sentence, not chain-of-thought), `interventionType`
(`RETRY`/`REENGAGE`/`ESCALATE`/`STOP`), `expectedRecoveryValue`, and
`urgency`. Raw AI output is never trusted: `RecoveryAgentService` validates
every field (known action, in-range confidence, non-negative expected
value, matching transaction id, no missing fields) before it is allowed
anywhere near the policy engine.

**Fails closed.** Any provider exception (network error, timeout, bug) or
any recommendation that fails validation is replaced with a safe fallback
(`ESCALATE`, confidence `0`, an explicit "AI unavailable" rationale) before
policy evaluation ever runs - never silently repaired, never allowed to
propagate into a broken endpoint. `AIRecommendationResponse.
providerAvailable` makes this state machine-readable.

**Four distinct numbers, never conflated:**

| Field | Range | Meaning |
|---|---|---|
| `riskScore` (Phase 3) | 0-100 | How large/urgent the exposure is |
| `recoveryProbability` (Phase 3) | 0.0-1.0 | Deterministic formula estimate of recovery likelihood |
| `confidence` (Phase 5, AI) | 0.0-1.0 | The AI's own stated confidence in its recommendation - never itself a safety signal |
| `expectedRecoveryValue` (Phase 5, AI) | currency | A **prediction** - never a claim of actual recovered money. Actual recovery will only ever be represented by `RecoveryAttempt.amountRecovered`, written by a future execution layer (Phase 7), not by this phase. |

**Audit trail.** Every `evaluate(transactionId)` call writes one
`RECOVERY_AI_RECOMMENDATION` audit row (`actor=AI_AGENT`) in addition to
whatever `RecoveryPolicyService` itself writes/dedupes for the resulting
policy check - so the full chain (Transaction → Risk → AI recommendation →
Policy decision) is reconstructable from `AuditLog` alone. The batch
endpoint (`evaluateAll()`) does **not** write one audit row per
transaction (500 rows per batch run would be audit noise for a
statistics-only call) - see `RecoveryAgentService`'s javadoc for the
tradeoff.

**Demo scenarios** (`RecoveryAgentDemoScenariosTest`, after a real Phase 3 analysis pass):

| Transaction | AI recommends | Policy decides | finalAction |
|---|---|---|---|
| `demo-easy-recovery` | `RETRY_PAYMENT` | `ALLOW` | `RETRY_PAYMENT` |
| `demo-high-value` | `RETRY_PAYMENT` | `ESCALATE` (amount) | `ESCALATE` |
| `demo-retry-escalation` | `ESCALATE` | `ESCALATE` (already under review) | `ESCALATE` |
| `demo-repeated-failure` | `STOP` | `STOP` (already halted) | `STOP` |
| `demo-successful-recovery` | `STOP` | `BLOCK` (already recovered) | `null` |

## Razorpay Integration / Payment Adapter (Phase 6)

`PaymentGateway` (`backend/src/main/java/com/recoverai/payment/`) is the
provider abstraction Phase 7 will use to actually execute a
policy-authorized recovery action:

```
Authorized future operation (Phase 7)
        |
        v
PaymentGateway                  <-- pure execution boundary, no authorization logic
        |
        v
MockPaymentGateway (default)  or  RazorpayPaymentGateway (opt-in)
        |
        v
PaymentExecutionResult          <-- structured; amountRecovered is a confirmed-recovery
        |                            field only, never set on link creation alone
        v
(Phase 7: persisted as a RecoveryAttempt, using a deterministic idempotency key)
```

**This phase ends at `PaymentExecutionResult`.** Nothing in this package
calls `RecoveryPolicyService`, writes a `RecoveryAttempt`, or is wired
into any controller — there is deliberately no `POST /api/payments/...`
endpoint. Building that orchestration (call the gateway only when policy
says `ALLOW`, persist the outcome, update transaction state) is Phase 7's
job; Phase 6 only proves the pieces it will need are correct in isolation.

**The gateway cannot decide whether an action is safe — structurally, not
just by convention.** `PaymentGateway`, `MockPaymentGateway`, and `
RazorpayPaymentGateway` hold no reference to `RecoveryPolicyService`, any
repository, or any risk/AI data — they only ever see a `
PaymentExecutionRequest` (transaction id, action, amount, currency,
idempotency key) that the caller has already had authorized elsewhere.
`RecoveryPipelineIsolationTest` proves this two ways: reflection confirms
neither `RecoveryAgentService` nor `RecoveryPolicyService` declares a
field of type `PaymentGateway` (so it is impossible for either to call
it), and a counting wrapper around the real gateway bean shows zero
invocations when a hypothetical caller correctly withholds execution on
`STOP`/`ESCALATE`, and exactly one when policy returns `ALLOW`.

**Only two actions are gateway-executable.** `RETRY_PAYMENT` and `
CREATE_PAYMENT_LINK` are payment operations; `SEND_RECOVERY_REMINDER`, `
ESCALATE`, and `STOP` are workflow/communication/policy actions and are
rejected with a structured `INVALID_REQUEST` result before any network
call. Razorpay has no generic "retry the original charge" API for
checkout-initiated payments, so `RazorpayPaymentGateway` implements
*both* actions the same, realistic way a merchant actually automates
this: creating a fresh Razorpay **Payment Link** (`POST /v1/payment_links`,
Basic Auth with `key_id:key_secret`, amount in paise) — documented
explicitly in its class javadoc rather than left implicit.

**`amountRecovered` is never inflated.** Creating or sending a payment
link is not confirmation that the customer paid it — that confirmation
can only come from a later provider webhook/status check, which is out of
scope for this phase. So `amountRecovered` is `BigDecimal.ZERO` on *every*
`PaymentExecutionResult` this phase can produce, success or failure alike
— `success=true` means "the provider call itself completed," never "money
was recovered." `simulated=true` on every `MockPaymentGateway` result
makes it impossible to mistake a mock outcome for a real one.

**Idempotency is a real database constraint, not an in-memory check.**
Migration V9 adds a unique constraint on `recovery_attempts.
idempotency_key`; `IdempotencyKeys.forAttempt(transactionId, action,
attemptNumber)` derives it deterministically — never `transactionId`
alone, since the same transaction can legitimately have several distinct
recovery attempts. `PaymentGatewayIdempotencyTest` proves the constraint
actually rejects a duplicate insert (H2); `PostgresMigrationIntegrationTest`
re-proves it against real PostgreSQL.

**Fails closed, always.** `PaymentGateway.execute()` never throws for an
ordinary provider failure (authentication failure, decline, timeout, rate
limit, malformed response, provider unavailable, amount/transaction
mismatch) — every one of these becomes a structured `success=false`
result with a `PaymentFailureReason`, so a caller always gets a
definitive outcome. Real credentials come only from environment variables
(`RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET`); a real call requires **both**
`recoverai.razorpay.enabled=true` **and** `mode=test` — two independent
opt-ins so the default configuration can never accidentally make a real
call. `RazorpayPaymentGatewayTest` confirms failure messages never
contain the configured secret.

## Recovery Execution Pipeline (Phase 7)

`RecoveryExecutionService` (`backend/src/main/java/com/recoverai/execution/`)
is the first component that actually connects every prior phase into one
flow:

```
Transaction
    |
    v
RecoveryAgentService.evaluate()   <-- Phase 5 (AI) + Phase 4 (policy), re-run fresh every call
    |
    v
PolicyDecision
    |
    v
   ALLOW? ----no----> BLOCK / ESCALATE / STOP  ->  executed=false, audit written, no gateway call
    |
   yes (and action is RETRY_PAYMENT/CREATE_PAYMENT_LINK)
    |
    v
attempt number = persisted attempt count + 1   (never in-memory, never random)
idempotency key = IdempotencyKeys.forAttempt(...)
    |
    v
already exists? --yes--> return that attempt's result (duplicate=true), no gateway call
    |
   no
    |
    v
reserve a PENDING RecoveryAttempt (unique constraint enforces "at most one")
    |
    v
PaymentGateway.execute()   <-- Phase 6, mock by default
    |
    v
persist SUCCESS/FAILED, provider, providerReference, amountRecovered
    |
    v
amountRecovered > 0 ?  --yes--> Transaction -> RECOVERED
    |
   no (true for every result today's gateways can produce)
    |
    v
Transaction status unchanged
    |
    v
Audit: RECOVERY_EXECUTION_STARTED -> COMPLETED/FAILED
```

**"Payment Link created" is not "money recovered."** This is the single
most important rule in this phase. `RecoveryExecutionService` only
transitions a transaction to `RECOVERED` when `result.success() &&
result.amountRecovered() > 0` — a condition today's `PaymentGateway`
implementations can never satisfy (both Mock and Razorpay always report
`amountRecovered=0`, since creating/sending a payment link is not itself
payment confirmation — see [Razorpay Integration](#razorpay-integration--payment-adapter-phase-6)).
The mapping is implemented correctly and tested directly (with a stub
gateway that reports a genuine non-zero confirmed amount) precisely so it
is proven correct for when a real confirmation mechanism exists, without
ever being reachable through today's actual gateways.

**"Execution success" is not "confirmed payment recovery."** A `SUCCESS`
`RecoveryAttempt` means the provider call itself completed without error
— for a payment link, that means "the link was created and (in a real
integration) sent," never "the customer paid." The response and the audit
trail both carry this distinction explicitly rather than collapsing it
into one boolean.

**Idempotency is layered, on purpose.** Two independent mechanisms
prevent duplicate execution, and they catch different scenarios:
- **Phase 4's existing `DUPLICATE_ACTION` policy check** — re-run fresh
  on every `execute()` call via `RecoveryAgentService.evaluate()` — is
  what stops a *sequential* repeat (call it again a minute later): it
  sees the first attempt's `SUCCESS` within the duplicate-action window
  and returns `BLOCK`, so the policy boundary remains the single source
  of truth for "should this run again" — Phase 7 does not duplicate that
  logic.
- **The database's unique constraint on `recovery_attempts.
  idempotency_key`** (migration V9) is what stops a *concurrent* race —
  two requests that both pass the pre-check before either commits. The
  losing insert throws, its entire attempt rolls back atomically (no
  partial writes, no stray audit rows), and a fresh transaction resolves
  the race by returning the winner's already-committed result.
  `RecoveryExecutionConcurrencyTest` proves this with two real threads:
  exactly one provider call, one `RecoveryAttempt` row, regardless of
  which thread "wins."

No Redis, no distributed lock, no new infrastructure — just the existing
PostgreSQL database's own ACID guarantees plus a plain `SELECT`
pre-check for the cheap common case.

**Attempt numbering is derived from persisted history, not memory.**
`attemptNumber = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(id).size() + 1`
— the same whole-transaction sequential convention the Phase 2 seed data
already used, kept consistent rather than introducing a second,
per-action counting scheme.

**A new migration, justified.** V10 adds `recovery_attempts.amount` (the
authorized execution amount, recorded as a point-in-time fact rather than
only derived via the `transactions` foreign key, backfilled from the
parent transaction for every pre-Phase-7 row) and `provider`/
`provider_reference` (nullable — only set once a real `PaymentGateway`
call has actually happened).

**No raw execution endpoint.** `POST /api/recovery/{transactionId}/execute`
takes no request body — the server derives the AI recommendation and
policy decision itself; a client cannot supply or override the action,
amount, currency, or policy decision. There is deliberately no
`POST /api/payments/execute` that would let a caller choose an arbitrary
action and bypass AI/policy (`RecoveryExecutionControllerTest` asserts
exactly this path returns 404).

**Demo scenarios** (`RecoveryExecutionDemoScenariosTest`):

| Transaction | Policy decision | Executed | Provider |
|---|---|---|---|
| `demo-easy-recovery` | `ALLOW` | `true` | `mock` (simulated, `amountRecovered=0`) |
| `demo-high-value` | `ESCALATE` | `false` | — |
| `demo-retry-escalation` | `ESCALATE` | `false` | — |
| `demo-repeated-failure` | `STOP` | `false` | — |
| `demo-successful-recovery` | `BLOCK` | `false` | — |

## Failure-Recovery Demo (Phase 8)

`RecoveryDemoService` (`backend/src/main/java/com/recoverai/demo/`) adds
**no new decision logic** — it is a read/aggregation layer over the real
Phase 3-7 pipeline, shaped for presentation:

```
For each of the 5 fixed demo transactions:
    RevenueRiskService.analyzeTransaction(id)      <-- real Phase 3 risk scoring
        |
        v
    RecoveryExecutionService.execute(id)           <-- real Phase 5 AI + Phase 4 policy +
        |                                              (only on ALLOW) real Phase 6 gateway
        v
    AuditLogRepository.findByTransactionIdOrderByTimestampAsc(id)  <-- real persisted trail
        |
        v
    RecoveryDemoScenarioResponse                   <-- shapes the above; invents nothing
```

`RecoveryDemoService` holds no field of type `PaymentGateway` or
`RecoveryPolicyService` (proven by reflection in `RecoveryDemoSafetyTest`,
the same pattern `RecoveryPipelineIsolationTest` uses for Phase 6/7) — it
is structurally impossible for the demo layer to call the payment gateway
directly or bypass the policy engine.

**Two endpoints:**

| Endpoint | Returns |
|---|---|
| `GET /api/demo/recovery` | All 5 scenarios plus aggregate metrics (`RecoveryDemoSummaryResponse`) |
| `GET /api/demo/recovery/{externalTransactionId}` | One scenario in full detail (`RecoveryDemoScenarioResponse`); 404 for anything other than the 5 fixed demo ids |

These are intentionally `GET` for demo convenience even though calling them
does exercise the real pipeline (risk is re-analyzed, `RecoveryExecutionService.
execute()` runs exactly as it would from Phase 7's own endpoint) — this is
documented on the controller rather than left as an implicit surprise.

**The five scenarios** (`RecoveryDemoServiceTest`, `RecoveryDemoSafetyTest`):

| Scenario | Transaction | AI recommends | Policy decides | Executed | Outcome badge |
|---|---|---|---|---|---|
| `EASY_RECOVERY` | `demo-easy-recovery` | `RETRY_PAYMENT` | `ALLOW` | `true` | `PENDING CONFIRMATION` (mock, `amountRecovered=0`) |
| `HIGH_VALUE_ESCALATION` | `demo-high-value` | `RETRY_PAYMENT` | `ESCALATE` (amount) | `false` | `NOT EXECUTED` |
| `REPEATED_FAILURE_STOP` | `demo-repeated-failure` | `STOP` | `STOP` (already halted) | `false` | `NOT EXECUTED` |
| `ALREADY_RECOVERED` | `demo-successful-recovery` | `STOP` | `BLOCK` (already recovered) | `false` | `NOT EXECUTED` |
| `ALREADY_ESCALATED` | `demo-retry-escalation` | `ESCALATE` | `ESCALATE` (already under review) | `false` | `NOT EXECUTED` |

The `HIGH_VALUE_ESCALATION` row is the buildathon's central safety claim
made visible: the AI's recommendation and the policy's actual decision are
shown side by side, and they differ — proving **"AI recommends. Policy
authorizes."** is a real behavior, not a slogan.

**"Execution success" is never shown as "money recovered."** The outcome
badge is derived purely from real response fields
(`executed`/`executionStatus`/`amountRecovered`) — see `outcomeLabel` in
`frontend/src/types/demo.ts`. A `SUCCESS` gateway call renders as `PENDING
CONFIRMATION`, never `SUCCESS`, unless `amountRecovered > 0` — a state no
gateway available today can produce. `confirmedAmountRecovered` (the
aggregate KPI) is summed only from real `amountRecovered` figures — never
from `potentialRecoveryValue`, `amountAtRisk`, or execution success —
so it is always `0.00` today, honestly.

**Repeatable by construction, no reset endpoint needed.** Re-running a
scenario does not require a special reset mechanism: `analyzeTransaction`
is an idempotent upsert (Phase 3), and a repeated `execute()` call is
naturally re-blocked by Phase 4's existing `DUPLICATE_ACTION` policy check
(or, for the already-resolved/escalated scenarios, the same policy
decision every time) — so no scenario can accumulate uncontrolled
`RecoveryAttempt` rows or contradictory transaction state merely from
being re-run. `RecoveryDemoSafetyTest.reRunningDemo_duplicateActionProtectionRemainsActive`
proves this directly. No destructive `POST /api/demo/reset` was added,
since nothing needs resetting.

**Audit timeline, real rows only.** Each scenario's `auditTimeline` is
exactly what `AuditLogRepository.findByTransactionIdOrderByTimestampAsc`
returns — nothing is inserted by the demo layer itself. For a seeded demo
transaction that carries prior history, this can include `RISK_DETECTED`/
`RECOVERY_ATTEMPT_RECORDED` rows the *seeder* wrote (`actor=SEED_SCRIPT`,
representing "what already happened to this transaction" — see
[Dataset](#dataset)), followed by the live `RECOVERY_AI_RECOMMENDATION`,
`RECOVERY_POLICY_EVALUATED`, and whichever `RECOVERY_EXECUTION_*` event
this actual run produced. There is no synthetic `RISK_ANALYZED` step from
a *live* Phase 3 analysis, though — `RevenueRiskService` itself does not
write an `AuditLog` row (Phase 3 was never wired to the audit trail —
deterministic risk scoring is treated as a queryable derived fact, not an
auditable decision); only the seeder's historical, clearly-labeled rows
can appear.

**Frontend: `/demo/recovery`.** A new React Router route
(`frontend/src/pages/RecoveryDemoPage.tsx`) renders 4 KPI cards (revenue at
risk, potentially recoverable, confirmed recovered, transactions at risk),
a static visual pipeline diagram ending in the "AI recommends. Policy
authorizes." tagline, the 5 scenarios as clickable cards with risk/policy/
outcome badges, and a detail panel (risk factors, AI rationale/confidence,
policy reason, execution result, and the real audit timeline) for whichever
scenario is selected. Run it with `npm run dev` (see
[Running locally](#running-locally)) and open `http://localhost:5173/demo/recovery`.

## What is real vs. simulated

This section will be kept accurate and complete as each capability lands.
As of Phase 8:

- The backend and frontend are real, running applications — nothing here
  is mocked.
- The database schema and all persisted data are real (a real PostgreSQL
  schema via Flyway, verified against a real — if temporary — PostgreSQL
  instance in tests). The *content* of that data is synthetic demo data,
  clearly documented as such — see [Dataset](#dataset).
- The Revenue Risk Engine (Phase 3) is a real, deterministic, fully
  computed scoring model — every `riskScore`/`recoveryProbability`/
  `amountAtRisk` value returned by its endpoints is genuinely calculated
  from the request's actual transaction/customer/attempt data, not
  hardcoded or looked up from a canned response. It is explicitly **not**
  a machine-learning model and makes no accuracy claim beyond "this
  deterministic formula, consistently applied" — see
  [Revenue Risk Engine](#revenue-risk-engine-phase-3).
- The Recovery Safety / Policy Engine (Phase 4) is a real, deterministic
  authorization boundary — every `ALLOW`/`BLOCK`/`ESCALATE`/`STOP`
  decision and every `policyChecks` entry is genuinely computed from the
  request's actual transaction/recovery-attempt/risk state, never
  hardcoded. It evaluates only — it does not execute any action, and
  writes no `RecoveryAttempt` row. See
  [Recovery Safety / Policy Engine](#recovery-safety--policy-engine-phase-4).
- The AI Recovery Agent (Phase 5) genuinely calls a real, deterministic
  provider (`MockAIRecoveryProvider`) and hands its output through the
  real Phase 4 policy engine — `aiRecommendation` and `policyDecision` in
  every response are both actually computed, never hardcoded. It does not
  execute any action, does not call Razorpay, and does not itself claim
  any money was recovered. `AnthropicAIRecoveryProvider` is real,
  functional code for genuine LLM inference, but has never been exercised
  against the real Anthropic API in this environment (no API key
  available) — see [Known limitations](#known-limitations).
- The Razorpay integration (Phase 6) is real, functional code — `
  RazorpayPaymentGateway` genuinely calls the documented Payment Links
  API when explicitly enabled — but **never runs during any automated
  test** and **has never been exercised against the real API** in this
  environment (no Test Mode credentials available); see
  [Known limitations](#known-limitations). `MockPaymentGateway` is
  explicit about being simulated (`simulated=true` on every result) and
  never claims `amountRecovered > 0`.
- The Recovery Execution Pipeline (Phase 7) is real, wired code -
  `POST /api/recovery/{transactionId}/execute` genuinely runs the AI
  recommendation, the policy decision, and (on `ALLOW`) a real
  `PaymentGateway` call, and genuinely persists the result as a
  `RecoveryAttempt`. With the default `mock` provider, every execution is
  `simulated=true` and `amountRecovered=0`; a real Razorpay Test Mode call
  requires explicit opt-in (`enabled=true` + `mode=test`) and has never
  been exercised against the live API in this environment (see
  [Known limitations](#known-limitations)). No transaction has ever been
  marked `RECOVERED` by this phase, in any test or manual run - only a
  genuinely confirmed non-zero recovered amount would trigger that, which
  no gateway available today can produce. Once a future phase adds real
  confirmed-payment detection (a webhook), every recovery action in the UI
  will be clearly labeled either a **REAL RAZORPAY TEST ACTION** or a
  **SIMULATED DEMO ACTION**, and this section will describe exactly which
  calls are real versus simulated in that live workflow.
- The Phase 8 demo (`GET /api/demo/recovery`, `/demo/recovery` frontend
  route) adds no new simulation of its own — it genuinely calls the real
  Phase 3 (`RevenueRiskService`) and Phase 7 (`RecoveryExecutionService`)
  services described above, live, every time it is requested, and reads
  genuinely persisted `AuditLog` rows for its timeline. Its KPIs are
  real aggregates over those real responses — `confirmedAmountRecovered`
  is `0.00` for the same underlying reason Phase 7's `amountRecovered` is
  always `0`: no gateway available today can produce a non-zero confirmed
  amount.

## Known limitations

- No persistent PostgreSQL/Docker was available in the environment this
  project was built in; see the note under
  [Setup instructions](#setup-instructions) for how migrations and the
  seed dataset were still verified against real PostgreSQL. The `local`
  H2 profile is for offline smoke-testing only, not a substitute for
  actually running PostgreSQL.
- The two read-only transaction endpoints (`GET /api/transactions`,
  `GET /api/transactions/{id}`) are minimal, added only to prove the
  persistence layer works over HTTP. The full dashboard/detail API
  (recovery attempts, audit trail) is a later phase.
- The Revenue Risk Engine's weights are illustrative synthetic values
  calibrated by hand against a handful of worked examples (see
  [Revenue Risk Engine](#revenue-risk-engine-phase-3)) — they are a
  reasonable, explainable, internally-consistent baseline, not a
  statistically fitted or externally validated model.
- `POST /api/revenue-risk/analyze-all` must be called explicitly; nothing
  runs risk analysis automatically yet (no scheduler, no event trigger).
- The Phase 4 policy engine's thresholds (`maxAutomaticRetryAttempts=2`,
  `maxAutonomousRecoveryAmount=₹25,000`, `maxRecoveryActionsPerTransaction=3`,
  `duplicateActionWindowHours=24`) are illustrative synthetic defaults, not
  a value derived from real merchant risk appetite.
- `maxAutonomousRecoveryAmount` doubles as the "high-value approval
  threshold" — a deliberate simplification of one configurable amount
  instead of two knobs with identical semantics in this prototype.
- Duplicate-action prevention is a fixed lookback window compared against
  each recovery attempt's `executedAt`, not a stateful in-flight lock — a
  concurrent double-submission within the same instant is not the target
  scenario this phase defends against (there is no execution to race yet).
- The policy engine's `RISK_FLAGS` check only escalates on `CRITICAL`; it
  does not otherwise let Phase 3's risk score influence the decision, by
  design — see [Recovery Safety / Policy Engine](#recovery-safety--policy-engine-phase-4).
- **`AnthropicAIRecoveryProvider` has never been exercised against the
  real Anthropic API** — no API key was available in this environment.
  It is written defensively (any failure throws, which `RecoveryAgentService`
  always catches and turns into a safe fallback), so a bug in it can only
  degrade to "AI unavailable," never break the endpoint or bypass policy —
  but its actual behavior against a live model is unverified. Treat it as
  a careful scaffold, not a proven integration.
- The mock provider's own "give up on retrying" heuristic
  (`STOP_ATTEMPT_THRESHOLD=4` prior attempts) is intentionally different
  from Phase 4's `maxAutomaticRetryAttempts=2` — the AI does not know or
  enforce Phase 4's exact thresholds, by design (see
  [AI Recovery Agent](#ai-recovery-agent-phase-5)), so its own sense of
  "too many attempts" is deliberately looser than policy's.
- Batch AI evaluation (`POST /api/recovery-agent/evaluate-all`) reuses the
  single-transaction pipeline per row rather than duplicating Phase 3/4's
  fetch-join optimizations, so it is O(n) queries per transaction, not
  O(1)-amortized like `RevenueRiskService.analyzeAllAtRisk()` — an
  intentional simplicity tradeoff for a recommendation-only, non-executing
  batch capability.
- No recovery execution orchestration exists yet; `finalAction` is
  currently a recommendation of what *would* run, never something that
  actually runs. `PaymentGateway` exists but nothing in production code
  calls it.
- **`RazorpayPaymentGateway` has never been exercised against the real
  Razorpay API** — no Test Mode credentials were available in this
  environment. It is written defensively (`execute()` never throws; every
  failure mode becomes a structured result), so a bug in it can only ever
  look like a provider failure, never bypass validation or silently claim
  success — but its actual behavior against a live endpoint is unverified.
- Both `RETRY_PAYMENT` and `CREATE_PAYMENT_LINK` map to the same real
  Razorpay operation (creating a Payment Link) — Razorpay has no generic
  "retry the original charge" API for checkout-initiated payments, so
  this is the realistic mapping, not a shortcut; documented in `
  RazorpayPaymentGateway`'s javadoc.
- Only `INR` is currently supported (`PaymentGatewayValidation`) — no
  currency conversion, matching the seeded demo dataset's currency.
- The idempotency key (`transactionId:action:attemptNumber`) is enforced
  by a real unique constraint; `attemptNumber` is now computed by
  `RecoveryExecutionService` (Phase 7) from persisted history, resolving
  what was an open question at the end of Phase 6.
- `PaymentAuditEvents.forResult(...)` (Phase 6) remains unused by Phase 7
  in production — `RecoveryExecutionService` writes its own, differently-
  scoped `RECOVERY_EXECUTION_*` lifecycle events (covering blocked/
  escalated/stopped outcomes that have nothing to do with the gateway at
  all, not just gateway results), matching the distinct event set the
  Phase 7 spec itself calls for. `PaymentAuditEvents` stays available,
  tested, and ready for a narrower "gateway executed something" event
  should a future phase want one specifically.

## Known limitations — Phase 7

- Duplicate-request protection has two layers with different guarantees:
  Phase 4's `DUPLICATE_ACTION` policy check (an approximate, time-window
  based rule) stops a *sequential* repeat; the database's unique
  constraint on `idempotency_key` (exact, ACID-guaranteed) stops a
  *concurrent* race. They were deliberately kept separate rather than
  merged into one mechanism — see
  [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7).
- The genuine concurrent-race path (`resolveDuplicate`, used only when
  two requests both pass the idempotency pre-check before either commits)
  returns a response with `recommendation`/`policyDecision` set to `null`
  — those were already reported to whichever request performed the
  evaluation; re-deriving them would call the AI a second time
  needlessly. This is documented on `RecoveryExecutionResponse` itself.
- `RecoveryExecutionService.execute()` always re-runs the full AI +
  policy evaluation on every call (per the spec's explicit "policy
  re-check immediately before execution" requirement) — there is no
  cheaper "just check the cache" path, so this endpoint does real work
  (including a full `MockAIRecoveryProvider` call and, in `anthropic`
  mode, a real LLM call) on every invocation, including ones that turn
  out to be blocked as duplicates.
- No transaction has ever been observed transitioning to `RECOVERED` by
  this phase (correctly - no gateway available today can produce a
  non-zero confirmed amount); the mapping that would do so is implemented
  and directly tested with a stub gateway, but is not exercised by any
  real or mock provider call in this codebase.
- Batch execution, recovery metrics, and a "₹X recovered" figure are
  explicitly out of scope for this phase — see
  [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7).

## Known limitations — Phase 8

- `GET /api/demo/recovery` and `GET /api/demo/recovery/{id}` are `GET`
  endpoints that are not side-effect-free — calling them re-runs real risk
  analysis and the real execution pipeline (which can write a
  `RecoveryAttempt`/audit rows the first time `demo-easy-recovery` is
  executed). This is a deliberate, documented exception for demo
  convenience (the Phase 8 spec asks for `GET`), not an accidental REST
  violation — see [Failure-Recovery Demo](#failure-recovery-demo-phase-8).
- The demo is hardcoded to the 5 fixed named seed transactions
  (`demo-easy-recovery`, `demo-high-value`, `demo-repeated-failure`,
  `demo-successful-recovery`, `demo-retry-escalation`) — it cannot run
  against an arbitrary transaction id. That is intentional (a curated,
  presentation-ready walkthrough), not a gap in the underlying pipeline,
  which already works for any transaction via the Phase 7 endpoint
  directly.
- No `POST /api/demo/reset` exists. It was deliberately not built:
  re-running the demo is already safe (see "Repeatable by construction"
  above), so a destructive reset endpoint would add risk without adding
  capability. A full dataset reset is still available via
  `DemoDataSeeder.seed()` — used directly by tests, and, as of the
  deployment phase, by the opt-in `DemoSeedRunner` at startup (not an
  HTTP endpoint — see
  [README § Buildathon Deployment](#buildathon-deployment) — `POST
  /api/demo/seed` itself remains unbuilt).
- The audit timeline shown per scenario reflects real `AuditLog` rows only
  — but "real" includes the seeder's own historical `RISK_DETECTED`/
  `RECOVERY_ATTEMPT_RECORDED` rows for transactions seeded with prior
  history, not just the live `RECOVERY_AI_RECOMMENDATION`/`RECOVERY_
  POLICY_EVALUATED`/`RECOVERY_EXECUTION_*` events this layer's own pipeline
  call produces. There is still no *live* `RISK_ANALYZED` event, because
  `RevenueRiskService` itself does not write to `AuditLog`. Adding one, if
  wanted, is Phase 3/9 scope, not Phase 8's (this layer only reads and
  shapes the real audit trail; it does not
  write to it).
- The frontend demo page has no dedicated component-level test suite yet
  (matching the rest of the frontend — see [Testing](#testing)); it is
  verified with `npm run build` (type-checks) and manual browser
  verification against the live backend.
- Concurrency across simultaneous demo requests is inherited entirely from
  Phase 7's own database-enforced idempotency (`RecoveryExecutionConcurrencyTest`)
  — the demo layer adds no additional locking of its own, and needs none.

## Buildathon Deployment

This section covers making the already-complete application (Phases 1-8)
reachable on the internet for the buildathon demo. **No product behavior
changed to produce this section** — every endpoint, safety rule, and
architectural boundary above is unchanged; this is deployment configuration
only (an explicit `prod` Spring profile, environment-variable audit, a
backend `Dockerfile`, and an opt-in demo-seeding mechanism).

### 1. PostgreSQL setup

Provision a managed PostgreSQL 16-compatible database (Render, Railway,
Supabase, Neon, or equivalent — any of these work; nothing here is
platform-specific). Take the connection details it gives you and set:

```
DB_URL=jdbc:postgresql://<host>:<port>/<database>
DB_USERNAME=<user>
DB_PASSWORD=<password>
```

Flyway (already enabled by default — see [Testing](#testing)) applies
migrations `V1`-`V10` automatically on the first startup against this
database; `spring.jpa.hibernate.ddl-auto=validate` then confirms every
entity mapping matches exactly. Nothing here is new — this is the same
migration set already verified against real PostgreSQL by
`PostgresMigrationIntegrationTest` throughout Phases 2-8 (see
[Verifying against real PostgreSQL](docs/ARCHITECTURE.md#verifying-against-real-postgresql-without-docker)).
**H2 must never be used for this** — that remains exclusively the `local`
profile's offline dev fallback (see [Setup instructions](#setup-instructions)).

### 2. Backend environment variables

See [`.env.example`](.env.example) for the full, categorized, deployment-
ready template (no real values in it, safe to commit). Summary:

| Variable | Purpose | Deployment default |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection | **Required** — set from your managed Postgres instance |
| `PORT` | Bind port; takes priority over `SERVER_PORT` | Set automatically by Render/Railway-style platforms |
| `SERVER_PORT` | Bind port, if `PORT` isn't platform-supplied | `8080` |
| `SPRING_PROFILES_ACTIVE` | `prod` for the explicit CORS/logging overlay (see below); unset also works (the default profile is already PostgreSQL/Flyway/validate) | `prod` recommended |
| `FRONTEND_URL` | Deployed frontend origin, for CORS | **Required** in the `prod` profile — no localhost fallback there |
| `CORS_ALLOWED_ORIGINS` | Backward-compatible alias for `FRONTEND_URL` | — |
| `AI_PROVIDER` | `mock` (default, safe) or `anthropic` | `mock` unless intentionally configuring Anthropic |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `ANTHROPIC_TEMPERATURE`, `ANTHROPIC_TIMEOUT_SECONDS`, `ANTHROPIC_MAX_TOKENS` | Real Claude inference | Only if `AI_PROVIDER=anthropic` |
| `RAZORPAY_ENABLED` | Master real-Razorpay opt-in | `false` |
| `RAZORPAY_MODE` | `simulation` (default) or `test` | `simulation` |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Razorpay Test Mode credentials | Only if `RAZORPAY_ENABLED=true` and `RAZORPAY_MODE=test` |
| `RAZORPAY_BASE_URL`, `RAZORPAY_TIMEOUT_SECONDS` | Razorpay API tuning | Defaults are fine |
| `DEMO_SEED_ENABLED` | Seed the deterministic demo dataset once at startup | `true` for the demo deployment only — see [Seed data](#7-seed-data) below |

### 3. Frontend environment variables

Only one: `VITE_API_BASE_URL`, the deployed backend's base URL (e.g.
`https://recoverai-backend.onrender.com`). Set it as a real environment
variable in your hosting platform's project settings (Vercel: Project →
Settings → Environment Variables) — Vite's build picks up real process
environment variables at build time in addition to `.env` files, so this
works regardless of `vite.config.ts`'s `envDir` setting. **Never** put a
secret in a `VITE_*` variable — everything with that prefix is bundled
into the public JS shipped to the browser (`frontend/src/lib/api.ts` is
the only place this project reads one).

### 4. Backend deployment

Using the provided `backend/Dockerfile` (multi-stage: Maven+JDK build
stage, slim `eclipse-temurin:17-jre-alpine` runtime stage, non-root user,
no secrets baked in):

1. Point your platform (Render "Web Service", Railway "Deploy from
   Dockerfile", or equivalent) at this repo with `backend/` as the build
   context / `backend/Dockerfile` as the Dockerfile path.
2. Set the environment variables from the table above.
3. Deploy. The container's `ENTRYPOINT` runs `java -jar app.jar`; the app
   reads `$PORT` (or `$SERVER_PORT`, or `8080`) to bind, runs Flyway
   against `$DB_URL` on startup, then serves traffic.

If your platform prefers native buildpack detection over Docker (Render
and Railway both support a plain Java/Maven service too), the equivalent
manual build/run commands are:

```bash
cd backend
mvn -DskipTests package
java -jar target/backend-0.1.0.jar
```

### 5. Frontend deployment

Vercel (or any static host that serves a Vite build):

1. Import this repo, set the project's **Root Directory to `frontend`**.
2. Build command: `npm run build` (already Vercel's Vite default).
   Output directory: `dist` (already Vercel's Vite default).
3. Set `VITE_API_BASE_URL` as a project environment variable (see above).
4. `frontend/vercel.json` adds the SPA rewrite (`/* → /index.html`) needed
   for React Router's client-side routes (`/demo/recovery`) to work on a
   direct visit or refresh, not just on in-app navigation.

### 6. Health check

`GET /api/health` (`HealthController`) — a minimal, stable
`{"status":"UP","service":"recoverai-backend","timestamp":"..."}`
response with **no database details, no credentials, no stack traces** —
use this as your platform's health-check URL. Spring Boot Actuator's
`GET /actuator/health` is also exposed but deliberately narrow:
`management.endpoints.web.exposure.include: health,info` and
`management.endpoint.health.show-details: never` mean it can only ever
report `{"status":"UP"}` or `{"status":"DOWN"}` — no dependency details,
no beans, no env, no secrets. No other Actuator endpoint is exposed.

### 7. Seed data

The demo needs the 5 named demo transactions (plus the bulk 500-
transaction dataset) actually present in the deployed database for
`/demo/recovery` to have anything to show. Set `DEMO_SEED_ENABLED=true`
for the demo deployment; `DemoSeedRunner` (an opt-in `ApplicationRunner`,
`@Profile("!test")`) then calls the existing, already-tested
`DemoDataSeeder.seed()` once at every startup. This is safe to leave on
for a demo environment specifically because `seed()` is deterministic and
idempotent — it wipes and regenerates the exact same dataset every time,
never accumulates duplicate rows. **Leave it `false` (the default) for any
environment that should not be reseeded on restart** — there is no
automatic seeding anywhere in this codebase unless this flag is explicitly
set. (`POST /api/demo/seed` as an on-demand HTTP trigger remains unbuilt —
see [Known limitations — Phase 8](#known-limitations--phase-8).)

### 8. AI configuration

Leave `AI_PROVIDER=mock` (the default) for the deployed demo unless you
are intentionally configuring a real Anthropic key — `MockAIRecoveryProvider`
is deterministic, offline, and exercised by the entire test suite, so the
demo is fully functional without any AI credentials. If you do set
`AI_PROVIDER=anthropic` and `ANTHROPIC_API_KEY`: the key is read only from
this environment variable (never hardcoded, never sent to the frontend),
is never logged (`AnthropicAIRecoveryProvider`'s only use of it is as an
outgoing `x-api-key` request header), never appears in `AuditLog`
metadata, and is never echoed back through any API response. Any provider
failure (bad key, network error, timeout, malformed output) already fails
closed to a safe `ESCALATE` fallback — unchanged from Phase 5, see
[AI Recovery Agent](#ai-recovery-agent-phase-5).

### 9. Razorpay Test Mode configuration

Leave `RAZORPAY_ENABLED=false` / `RAZORPAY_MODE=simulation` (the default)
for the deployed demo — `MockPaymentGateway` is deterministic, offline,
and every execution is clearly `simulated=true`. Only if you intentionally
have real Razorpay **Test Mode** credentials, set **both**
`RAZORPAY_ENABLED=true` **and** `RAZORPAY_MODE=test` (two independent
opt-ins, deliberately redundant — see
[Razorpay Integration](#razorpay-integration--payment-adapter-phase-6)) —
**never** production Razorpay credentials in this deployment phase. Every
credential is read only from environment variables, is never logged
(`RazorpayPaymentGateway`'s only use of the key/secret is Basic-Auth-
encoding it into an outgoing request header), and is never returned by
any API response — confirmed by `RazorpayPaymentGatewayTest` and re-
confirmed by this phase's repository-wide secret scan (see Security notes
below).

### 10. Smoke testing

The following real endpoints (all pre-existing — none invented for this
phase) were exercised against a live backend instance during this
deployment phase, with `DEMO_SEED_ENABLED=true`:

| Endpoint | Result |
|---|---|
| `GET /api/health` | 200, `status: UP` |
| `GET /api/transactions` | 200, real paginated data (500 seeded rows) |
| `GET /api/revenue-risk/metrics` | 200, real aggregate figures |
| `POST /api/revenue-risk/analyze-all` | 200, `transactionsAnalyzed: 197` |
| `POST /api/recovery-agent/evaluate-all` | 200, real per-action/decision counts |
| `GET /api/demo/recovery` | 200, all 5 scenarios with real risk/AI/policy/execution results |
| `GET /api/demo/recovery/demo-easy-recovery` | 200 |
| `POST /api/recovery/{transactionId}/execute` | 200 — first call executes (`ALLOW`, mock provider); a second call on the same transaction is correctly `BLOCK`ed by Phase 4's duplicate-action check, proving policy enforcement survives a real deployment-shaped run, not just tests |
| `POST /api/recovery/{unknown}/execute` | 404 |
| `POST /api/payments/execute` | 404 — confirms no raw execution endpoint exists |
| CORS preflight from the configured `FRONTEND_URL`/`CORS_ALLOWED_ORIGINS` origin | 200 with `Access-Control-Allow-Origin` echoing that origin |
| CORS preflight from an arbitrary, non-configured origin | 403 — cross-origin requests from anywhere else are genuinely rejected, not just documented as rejected |

Frontend: `npm run build` then `npm run preview` — `/` and `/demo/recovery`
(a client-side route) both return `200` directly (no dev server, no
client-side-only routing trick), confirming the SPA fallback works the
same way a static host's rewrite rule needs it to.

### 11. Security notes

- Repository-wide search for API keys, passwords, secrets, Authorization
  headers, and provider credentials found none — every credential
  (`ANTHROPIC_API_KEY`, `RAZORPAY_KEY_ID`/`KEY_SECRET`/`WEBHOOK_SECRET`,
  `DB_PASSWORD`) is read exclusively from environment variables via Spring
  `@ConfigurationProperties`/`@Value`, with empty-string defaults — never a
  literal value in source.
- No secret is ever logged, returned by an API response, or written to
  `AuditLog` metadata — verified by direct code inspection of
  `AnthropicAIRecoveryProvider`/`RazorpayPaymentGateway` (the only two
  classes that ever hold a real credential) and by the existing
  `RazorpayPaymentGatewayTest`/`PaymentAuditEventsTest` test coverage.
- `.env` is gitignored (`.env`, `.env.local`, `*.env`, with `.env.example`
  explicitly excepted); `backend/target/`, `frontend/dist/`,
  `frontend/node_modules/`, and `backend/.data/` are all gitignored and
  were confirmed absent from `git status`/`git diff --cached` before this
  phase's changes were staged.
- `git status` after staging this phase's changes shows only legitimate
  source/config/doc files — no generated artifacts, no `.env`, no `.data/`.
- Actuator is deliberately narrow (`health,info` only, `show-details: never`)
  — no `/actuator/env`, `/actuator/beans`, or `/actuator/configprops` is
  exposed, so process environment variables (including every credential
  above) can never leak through Actuator even if scanned/enumerated.
- CORS in the `prod` profile has no localhost fallback — an operator must
  explicitly set `FRONTEND_URL`/`CORS_ALLOWED_ORIGINS`, and a live
  preflight test against a non-configured origin returned `403` (see
  Smoke testing above), not merely "documented as restricted."
- No architectural safety boundary changed: `RecoveryPolicyService` is
  still the sole authorization boundary, `RecoveryExecutionService` is
  still the only execution path, `PaymentGateway` is still never reachable
  from the frontend or any endpoint other than through that one path, and
  no transaction can be marked `RECOVERED` without a genuinely confirmed
  `amountRecovered > 0` (still unreachable with today's providers — see
  [Recovery Execution Pipeline](#recovery-execution-pipeline-phase-7)).

### 12. Live deployment (Phase 9)

The application described above is deployed and was verified live at the
following real, internet-reachable URLs — not just prepared for
deployment:

| Component | Provider | URL |
|---|---|---|
| Frontend | Vercel | https://recoverai-bay.vercel.app |
| Backend API | Render (Docker deploy from `backend/Dockerfile`) | https://recoverai-xrky.onrender.com |
| Health check | — | https://recoverai-xrky.onrender.com/api/health |
| Demo page | — | https://recoverai-bay.vercel.app/demo/recovery |
| Database | Neon (managed PostgreSQL 16) | private connection, not publicly listed |

**Backend environment actually configured on Render:**
`SPRING_PROFILES_ACTIVE=prod`, `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`
(pointed at the Neon instance above, `sslmode=require`), `FRONTEND_URL=
https://recoverai-bay.vercel.app`, `AI_PROVIDER=mock`,
`RAZORPAY_ENABLED=false`, `RAZORPAY_MODE=simulation`,
`DEMO_SEED_ENABLED=true`. No credential is committed to source; all of the
above were set directly in Render's dashboard.

**Frontend environment actually configured on Vercel:**
`VITE_API_BASE_URL=https://recoverai-xrky.onrender.com` (Production
environment only). Confirmed by inspecting the built JS bundle: the axios
`baseURL` compiled into it is the real Render URL, not `localhost`.

**What was verified against the real, deployed system (not local, not
mocked):**

- Flyway applied all of `V1`-`V10` to the real Neon database and
  `spring.jpa.hibernate.ddl-auto=validate` passed — confirmed twice: once
  via a direct local connection to the Neon instance, and independently in
  Render's own startup logs (`Successfully validated 10 migrations`,
  `Schema "public" is up to date`).
- `GET /api/health` → `200 {"status":"UP",...}` on the live Render URL.
- `GET /api/transactions`, `GET /api/revenue-risk/metrics`,
  `POST /api/revenue-risk/analyze-all`, `POST /api/recovery-agent/evaluate-all`
  all returned `200` with real, non-empty data computed from the live
  seeded dataset (500 transactions, 197 flagged at-risk after analysis).
- `GET /api/demo/recovery` on the live URL returned all 5 named scenarios
  with the exact expected outcomes: `demo-easy-recovery` → AI recommends
  `RETRY_PAYMENT` → policy `ALLOW` → executed through the mock gateway
  (`simulated=true`, `amountRecovered=0.00`, transaction status stays
  `FAILED`, not falsely `RECOVERED`); `demo-high-value` → AI recommends
  `RETRY_PAYMENT` but policy overrides to `ESCALATE` → **not** executed;
  `demo-repeated-failure` → policy `STOP` → not executed;
  `demo-successful-recovery` → policy `BLOCK` → not executed;
  `demo-retry-escalation` → policy `ESCALATE` → not executed. Aggregate
  `confirmedAmountRecovered: 0.00` and `gatewayCalls: 1` (only the one
  `ALLOW` case) — matching the safety guarantees in
  [Failure-Recovery Demo](#failure-recovery-demo-phase-8) exactly.
- CORS verified live in both directions against the real deployed frontend
  origin: a preflight from `https://recoverai-bay.vercel.app` →
  `200` with `Access-Control-Allow-Origin` echoing that exact origin; a
  preflight from an unrelated origin (`https://evil.example.com`, and
  separately the placeholder origin used before the frontend existed) →
  `403` on both, confirming CORS actually tightened to only the real
  frontend rather than staying open to whatever was configured first.
- `backend/Dockerfile` is now genuinely build-and-run verified, not just
  written — Render built it directly from this repository and it produced
  a working container serving real traffic, resolving the earlier
  "written but unverified" caveat.

**Known limitations of this live deployment specifically:**

- Both the backend (Render) and database (Neon) are on free tiers. Render
  spins the backend down after a period of inactivity; the first request
  after that can take 30 seconds to several minutes to respond while it
  cold-starts (observed directly during verification). This is a platform
  characteristic, not an application bug.
- During the initial deploy, the Render instance's own startup seed
  briefly collided with a one-time manual verification seed run against
  the same database from a separate process, causing one write to fail
  and that container to crash; Render's automatic restart then completed
  the seed cleanly on the next attempt with no lasting effect. This was a
  one-time coincidence from parallel verification, not a recurring
  failure mode — a normal deploy only ever seeds once, from one process.
- `DEMO_SEED_ENABLED=true` re-seeds (wipes and regenerates the same
  deterministic dataset) on every backend restart. This is intentional
  (see [Seed data](#7-seed-data) above) but means any demo scenario that
  was executed in a prior session resets to its pristine state after a
  restart — expected, not a bug, but worth knowing before presenting live
  (don't trigger a redeploy mid-demo unless a clean reset is wanted).
- No custom domain is configured — both URLs above are the platforms'
  default subdomains.
- `AI_PROVIDER=mock` and `RAZORPAY_MODE=simulation` remain the live
  defaults, per this task's explicit instruction not to enable real
  external credentials without being separately asked.

## Audit, Compliance & Production Hardening

Phase 10 is a security/compliance audit and hardening pass over the
already-complete, already-deployed system (Phases 1-9) — **no new product
features, no architectural changes, no weakened safety boundary.** The
core invariant is unchanged and was re-verified, not just re-asserted:

```
AI RECOMMENDS -> POLICY AUTHORIZES -> EXECUTION EXECUTES -> AUDIT RECORDS
```

This is a **compliance-oriented technical hardening pass and demo**, not a
certification. It does not claim PCI DSS, GDPR, RBI, or any other formal
regulatory compliance.

### Security audit

A repository-wide search (tracked files, working tree, and disk — not
just staged changes) for hardcoded secrets, API keys, database passwords,
private keys, and credentials in code/tests/docs found **none**. `.env`
and every real `.env.*` variant remain gitignored (`.env.example` is the
only intentional exception); no build artifact (`target/`, `dist/`,
`node_modules/`, `.data/`) is tracked. Every credential
(`DB_PASSWORD`, `ANTHROPIC_API_KEY`, `RAZORPAY_KEY_ID`/`KEY_SECRET`/
`WEBHOOK_SECRET`) is read only from an environment variable via
`@ConfigurationProperties`/`@Value`, with an empty-string default — never
a literal value in source. Frontend source was checked separately: the
only `VITE_*` variable this project reads is `VITE_API_BASE_URL` (never a
secret).

### AI authorization boundary

Structurally: `RecoveryAgentService` and `RecoveryPolicyService` hold no
field of type `PaymentGateway` (`RecoveryPipelineIsolationTest`, proven by
reflection — not merely untested, *impossible*). Behaviorally: the AI's
recommendation and the policy's final decision are independent fields in
every response, and execution only ever follows `PolicyDecision.ALLOW`.
This phase added two adversarial cases that were previously exercised only
implicitly:

- **Mismatched `transactionId`** in an AI recommendation — already
  rejected by `RecoveryAgentService.isValid()`, now explicitly tested
  (`RecoveryAgentServiceTest.mismatchedTransactionId_isRejected_
  fallsBackSafely`), falls back to a safe `ESCALATE`.
- **`CREATE_PAYMENT_LINK` recommended for an already-`RECOVERED`
  transaction** — blocked unconditionally by policy's transaction-status
  check regardless of the recommended action, now explicitly tested
  (`aiRecommendsPaymentLink_transactionAlreadyRecovered_isBlockedRegardless`).

Every other adversarial case this phase's spec called for (provider throws,
malformed JSON, invalid confidence, negative expected value, AI-vs-policy
disagreement on amount/retry/stop limits) was already covered by the
existing `RecoveryAgentServiceTest` suite from Phase 5 — reviewed and
confirmed still correct, not duplicated.

### Payment / Razorpay safety

`RazorpayPaymentGateway` and `MockPaymentGateway` reviewed line by line:
the API key/secret are used only to build the outgoing `Authorization`
header, are never logged, never appear in audit metadata, and are never
echoed in any response (log statements only ever include a transaction id,
HTTP status code, or exception class name). Provider errors are sanitized
before ever reaching a caller — a raw non-2xx response, malformed JSON, an
amount mismatch, or a network failure all map to a structured, generic
`PaymentExecutionResult` with a category (`PaymentFailureReason`), never
the raw provider response body. No automatic retry occurs anywhere in this
path — `RazorpayPaymentGateway.execute()` makes exactly one call and
returns. `PaymentGatewayValidation` independently re-validates action,
amount, and currency (INR-only) even though every field it checks is
already server-derived — defense in depth, not the primary boundary.
`RAZORPAY_ENABLED=false` / `RAZORPAY_MODE=simulation` remain the deployed
defaults; this phase did not enable real Razorpay calls.

### Idempotency & concurrency

Unchanged from Phase 7, re-verified: a duplicate request is caught cheaply
by a `SELECT` before ever attempting an insert; a genuine concurrent race
between two threads is resolved by the database's own unique constraint on
`recovery_attempts.idempotency_key` (migration `V9`) — the losing insert's
`DataIntegrityViolationException` is caught and the loser is handed the
winner's already-committed result in a fresh transaction. `RecoveryExecutionConcurrencyTest`
fires concurrent requests at the same transaction and asserts exactly one
gateway invocation and no inconsistent state — reviewed and confirmed
still passing; no change was made to this mechanism.

### Transaction state safety

`RecoveryExecutionService` only ever transitions a transaction to
`RECOVERED` when `result.success() && result.amountRecovered() > 0`.
Today's providers (mock and Razorpay) can never satisfy that condition —
both always report `amountRecovered = 0`, since creating/sending a payment
link is not confirmed payment. `RecoveryExecutionServiceTest.
providerFailure_transactionNeverMarkedRecovered` and the mock/Razorpay
mapping tests confirm this holds even on a reported "success". No route in
this codebase can mark a transaction `RECOVERED` without a genuinely
confirmed non-zero amount, and none currently can produce one.

### Audit trail

Every meaningful decision is recorded: risk detection, AI recommendation,
policy evaluation, execution start/completion/failure, escalation, stop,
block. `AuditLog.metadata` contains only operational facts (provider name,
decision, amount, action, confidence) — reviewed and confirmed it never
contains a password, API key, Authorization header, or raw provider
response. Both `RecoveryPolicyService` and `RecoveryAgentService` dedupe
identical repeated evaluations against the same transaction+action/decision
pair before writing a new audit row (an evaluation endpoint is expected to
be polled without flooding the trail) — an intentional tradeoff, unchanged
by this phase, documented here for visibility rather than left implicit.

### PII / data-minimization review

`Customer` name and email are the only PII this system stores.
`TransactionSummaryResponse` returns `customerName` only (needed for a
merchant to identify whose transaction it is). `TransactionDetailResponse`
previously returned the customer's **full, unmasked email address** from
an endpoint with no authentication — reviewed and found unnecessary
(nothing in this project's frontend currently reads it) and **fixed**:
`customerEmail` is now partially masked before it ever leaves the server
(`j***e@example.com`), preserving the field's purpose (recognizing a
repeat customer) while minimizing exposure. `RecoveryAgentContext` — the
only data ever sent to a third-party AI provider — was independently
confirmed to already exclude name/email; only IDs, amounts, statuses, and
aggregate counts are sent.

### Error handling

Spring Boot's default error handling was verified **live**, not assumed:
a malformed request (bad UUID, unknown route) returns only
`{timestamp, status, error, path}` — no stack trace, no exception class,
no SQL, no internal path, confirmed by direct testing against the
deployed backend. This phase adds `GlobalExceptionHandler`
(`@RestControllerAdvice`) as an explicit safety net for anything not
already handled by a controller-local handler: a malformed path parameter
now returns this API's normal `{"error": "..."}` shape instead of Spring's
generic body, and any genuinely unexpected exception is guaranteed to be
logged server-side with full detail while the client only ever sees a
generic, safe message. `server.error.include-stacktrace/exception/message/
binding-errors` are also now pinned explicitly in `application.yml` to
Spring Boot's own safe defaults — self-documenting rather than implicit.
Existing controller-local handlers (`TransactionNotFoundException` -> 404,
etc.) are unaffected — Spring always prefers the more specific handler.

### CORS & HTTP security headers

CORS unchanged and re-verified: `WebConfig` restricts `/api/**` to exactly
`FRONTEND_URL`/`CORS_ALLOWED_ORIGINS` (no `*`, no `allowCredentials`, so no
wildcard+credentials risk exists regardless). This phase adds
`SecurityHeadersFilter`, applying safe headers to every response —
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: strict-origin-when-cross-origin`, a minimal
`Permissions-Policy`, `Cache-Control: no-store`, and
`Strict-Transport-Security` — none of which can break a JSON-only API with
no HTML views.

### Rate limiting / abuse protection

The endpoints that do real, non-trivial work per call — AI evaluation
(`/api/recovery-agent/evaluate*`), batch risk analysis
(`/api/revenue-risk/analyze-all`), and recovery execution
(`/api/recovery/{id}/execute`) — previously had no protection against
uncontrolled repeated requests. This phase adds `RateLimitFilter`: a
lightweight, in-memory, per-client (via `X-Forwarded-For`, falling back to
the remote address) fixed-window limiter (default 20 requests/60s,
configurable via `RATE_LIMIT_*` env vars), returning `429` with
`Retry-After` when exceeded. **Deliberately not infrastructure-backed** —
no Redis, no gateway — appropriate for this single-instance buildathon
deployment specifically because it introduces no new dependency.
**Production recommendation**: a real multi-instance deployment needs a
shared store (Redis) or edge/gateway-level rate limiting, since this
filter's counters are per-instance and reset on restart.

### Actuator / health endpoint review

Unchanged, re-confirmed: `GET /api/health` returns a fixed, minimal
`{status, service, timestamp}` — no dependency details ever. Actuator
exposes only `health,info` (`management.endpoints.web.exposure.include`),
with `management.endpoint.health.show-details: never`, so
`/actuator/health` can only ever report `{"status":"UP"}` or
`{"status":"DOWN"}`. `/actuator/info` returns `{}` (no `build-info`/
`git-info` goal is configured in `pom.xml`). No `/actuator/env`,
`/actuator/beans`, `/actuator/configprops`, `/actuator/mappings`, or
`/actuator/heapdump` is exposed.

### Database hardening

Migrations `V1`-`V10` reviewed: 76 `NOT NULL`/`CHECK`/`UNIQUE`/
`FOREIGN KEY`/index definitions across the schema; the only `DROP` in any
migration is `V8`'s `DROP INDEX IF EXISTS` immediately superseded by an
equivalent `UNIQUE` constraint (which Postgres backs with its own index) —
no data-destructive statement exists anywhere. `ddl-auto: validate`
remains authoritative in every profile except `local` (H2, dev-only);
Flyway remains the only source of schema change. No migration was added,
removed, or modified by this phase.

### Logging / observability

Every `log.*` call in the codebase was searched for request bodies,
credentials, and headers — none found; the only fields logged around
payment/AI calls are a transaction id, an HTTP status code, or an
exception class name. **Production recommendation**: this deployment logs
to Render's console log only (no structured logging, no external
APM/error tracker, no uptime monitor). A real production deployment should
add structured JSON logging plus an external log sink, an APM/error
tracker (e.g. Sentry), and uptime/latency alerting — none of which this
phase added, to avoid introducing new infrastructure beyond what a
buildathon demo needs.

### Dependency / build audit

Backend `pom.xml` reviewed: every dependency is used for a clear purpose
(`h2` for the offline `local`/`test` profiles, `webflux` for the AI/
Razorpay `WebClient` calls, `embedded-postgres` for real-Postgres
integration testing) — no unnecessary or duplicated dependency found, no
change made. Frontend `package.json`: **`recharts` was listed but never
imported anywhere in `frontend/src`** — removed (`npm install` afterward
removed 39 now-unnecessary transitive packages, `npm audit` reports 0
vulnerabilities, production bundle unaffected apart from being smaller).
No dependency version was upgraded as part of this audit.

### New security regression tests (Phase 10)

`RecoveryAgentServiceTest` (+2): mismatched-transactionId rejection,
`CREATE_PAYMENT_LINK`-on-`RECOVERED` blocked. `TransactionControllerTest`
(+2): masked email in the API response, normalized 400 for a malformed
UUID path variable. `RateLimitFilterTest` (new, 7 cases): under/over
limit, per-client isolation, unguarded paths never limited, the execute
endpoint is guarded, the master `enabled` switch, `X-Forwarded-For`
precedence. `SecurityHeadersFilterTest` (new, 1 case): every response
carries the expected headers. All 16 items on this phase's security
regression checklist were confirmed — 14 were already covered by the
existing Phase 4-8 test suites (reviewed, not duplicated); the 2 genuine
gaps (mismatched transactionId, secrets-in-logging as a manual code-review
item rather than an automated assertion) are addressed above.

### Known limitations / production recommendations

- **No authentication or authorization exists on any endpoint.** Every
  API in this project, including `POST /api/recovery/{id}/execute`, is
  reachable by anyone with the URL — safety comes entirely from the
  policy engine deciding *whether* an action is authorized, not from
  restricting *who* can ask. This is a deliberate scope boundary (adding
  auth is a substantial product feature, not a hardening change, and this
  task's instructions were explicit: no new product features) but is the
  single most important gap before this could handle real payments data.
  **Recommendation**: API-key or OAuth-based merchant-scoped access before
  any real deployment beyond a demo.
- The rate limiter (above) is per-instance and in-memory — resets on
  restart, does not coordinate across multiple instances.
- This deployment is a single Render instance with no high-availability
  failover, and Render's free tier cold-starts after inactivity (see
  [Live deployment](#12-live-deployment-phase-9)).
- No external monitoring/alerting is configured (see Logging above).
- The revenue-risk scoring weights remain application-invented,
  illustrative values (see [Revenue Risk Engine](#revenue-risk-engine-phase-3)) —
  unrelated to this phase, restated here for completeness of the
  compliance picture.

## Interactive Recovery Console (Phase 11)

The `/demo/recovery` frontend page (Phase 8) was upgraded from a static,
one-shot snapshot into a genuinely interactive operational console. **No
new business logic, no new safety boundary, no architectural change** —
every action below is a thin call to an already-existing (or, for audit,
newly added read-only) backend endpoint; the frontend still computes
nothing itself.

### What changed

- **Per-scenario actions**, each independently clickable with its own
  loading/error state: **Analyze risk** (`POST /api/revenue-risk/analyze/{id}`),
  **Get AI recommendation** (`POST /api/recovery-agent/evaluate/{id}` —
  one real call that returns both the AI's recommendation and the
  policy's decision on it), **Evaluate policy** (`POST /api/recovery-policy/evaluate/{id}`,
  a standalone re-check showing every individual policy check pass/fail),
  **Execute recovery** (`POST /api/recovery/{id}/execute`), and
  **Refresh audit** (`GET /api/audit/{id}`, new — see below).
- **A guided "▶ Run demo" flow** per scenario: Analyzing risk → Getting AI
  recommendation → Ready for execution (or Blocked/Escalated/Stopped,
  with the real backend reason shown) → stops and waits for an explicit
  click on "Execute recovery" → Executing → Completed. It never executes
  automatically - `ALLOW` only ever unlocks the button, exactly as
  section 2 of this phase's spec required.
- **`BLOCK`/`ESCALATE`/`STOP` banners** with the real backend reason
  ("Execution blocked" / "Human approval required" / "Recovery stopped"),
  and the Execute button is disabled with a visible tooltip explaining
  why whenever the latest known policy decision isn't `ALLOW` or the
  transaction has already been executed.
- **A centralized, typed API client** (`frontend/src/lib/api.ts`) — every
  backend call goes through it, all reading `VITE_API_BASE_URL` (never a
  hardcoded URL), with a shared error normalizer (`toApiError`) that
  turns any Axios failure into a safe, user-facing message and correctly
  distinguishes a `429`, a `503`, a genuine network error, and a likely
  Render cold-start (a request that times out or never gets a response)
  so the UI can say "Connecting to RecoverAI backend… this can take up to
  a couple of minutes" instead of looking broken.
- **A new read-only endpoint**, `GET /api/audit/{transactionId}`
  (`AuditController`) — added because nothing previously exposed a
  transaction's audit trail outside the bundled demo endpoint, and that
  endpoint's `GET` re-runs the real evaluate/execute pipeline as a side
  effect (existing Phase 8 behavior, unchanged), which would be an
  unwanted side effect for a plain "refresh the audit panel" action. This
  new endpoint is a pure read: it checks the transaction exists (404 if
  not) and returns its `AuditLog` rows chronologically — no new decision
  logic, same projection Phase 8 already used internally.

### Safety boundaries preserved (re-verified, not just re-asserted)

- The execution endpoint still takes only a transaction id — no request
  body, so the frontend cannot supply its own amount, currency, or
  action. Verified live: a second execute call on an already-executed
  transaction is independently blocked by the backend's duplicate-action
  check (`executed: false`) regardless of what the frontend's button
  state does.
- Every policy decision, risk score, and AI recommendation shown is the
  literal backend response - the frontend performs no client-side
  authorization, retry-limit, or amount-limit logic anywhere.
- Verified live end-to-end against production: `demo-easy-recovery`
  (fresh, unexecuted) → Analyze risk → Get AI recommendation (`ALLOW`) →
  Execute recovery → `executed: true`, `amountRecovered: 0.00`,
  transaction status stayed `FAILED` (never falsely `RECOVERED`); a
  second execute attempt on the same transaction → `executed: false`
  (duplicate-blocked); `demo-high-value` (`ESCALATE`) → execute attempt →
  `executed: false`, `provider: null` (no gateway call). Audit timeline
  confirmed to grow with each real action via the new endpoint.

### Known limitation of this phase's verification

No browser-automation tool is available in this environment, so every
interaction above was verified at the API level (the exact call each
button makes, confirmed live against the deployed backend) and by a
clean `npm run build`/`tsc -b` (which fails on any type mismatch between
the frontend and the real DTOs) — not by literally clicking through the
rendered page in a browser. The visual/UX result should be spot-checked
manually at https://recoverai-bay.vercel.app/demo/recovery.
