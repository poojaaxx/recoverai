# RecoverAI — AI Revenue Recovery Agent

**Built for the Razorpay Buildathon, Track 03.**

> Detect revenue at risk → recommend the right intervention → enforce
> deterministic safety → execute bounded recovery → verify payment →
> measure recovered revenue.

**Live app:** https://recoverai-bay.vercel.app/demo/recovery
**API health check:** https://recoverai-xrky.onrender.com/api/health

Payments fail for reasons that have nothing to do with a customer's intent
to pay — an expired card, a bank's risk rule, a temporary network blip.
RecoverAI closes the loop from *detecting* that failure to *verifying* real
recovery, without ever letting an AI model authorize or execute a financial
action.

## Why this matters

Failed payments, checkout abandonment, and subscription-payment failures
are measurable revenue leakage — money a customer often would have paid,
that the merchant never collects because nobody followed up correctly.
Left to blunt automation, the fix is often worse than the problem:

- Not every failed payment should be retried — some should be left alone.
- High-value payments need stronger controls than a routine small retry.
- Repeated failures need a stopping rule, or automation never stops.
- Every recovery action needs to be auditable — "why did the system do
  that?" has to have a real, reconstructable answer.
- Running a recovery action is **not** the same as recovering money. A
  system that conflates the two is misreporting its own results.

RecoverAI is built around closing this loop honestly, with a hard boundary
between the part that *thinks* (AI) and the part that *decides*
(deterministic policy).

## The complete loop

```
Transaction (payment failure)
   |
   v
Revenue Risk Detection        deterministic: how much is at risk, how recoverable
   |
   v
AI Recommendation             diagnoses the failure, proposes ONE action + confidence + rationale
   |
   v
Deterministic Safety Policy   the only thing that can authorize a money-moving action
   |
   v
ALLOW / BLOCK / ESCALATE / STOP
   |
   v
Recovery Execution            calls the payment provider, only when ALLOW
   |
   v
Payment Provider              Razorpay Test Mode, or an honest simulation adapter
   |
   v
Verified Webhook Confirmation signature-checked, correlated, amount/currency re-verified
   |
   v
Recovered Revenue Metrics     counts only money a verified confirmation actually proved
```

Every stage above is a real, tested code path, not a diagram drawn ahead of
the implementation — see [Build quality](#build-quality--why-trust-it) below.

## AI Judgment: what AI does vs. what AI cannot do

**AI recommends. Policy authorizes. Execution executes. Webhook
confirmation measures.**

| AI does | AI cannot do |
|---|---|
| Analyze transaction, customer, and risk context | Authorize a payment |
| Recommend one recovery action | Bypass retry limits |
| Provide a confidence score | Bypass amount limits |
| Provide a concise, structured rationale | Override duplicate-action protection |
| Estimate expected recovery value | Override a human-approval requirement |
| | Override a STOP decision |
| | Write a `RecoveryAttempt` record |
| | Call `PaymentGateway` directly |
| | Mark a transaction `RECOVERED` |

The right-hand column isn't a policy promise, it's a structural fact: the
AI recommendation service (`RecoveryAgentService`) has no dependency on
`PaymentGateway` or the attempt-persistence layer, and every recommendation
still passes through the same deterministic `RecoveryPolicyService` check
whether it came from the AI or a test calling the policy engine directly.
The AI also never exposes model chain-of-thought — only the structured
action/confidence/rationale fields above.

A deterministic policy engine (`RecoveryPolicyService`) is the *only* thing
that can approve a money-moving action — it checks retry limits, amount
ceilings, duplicate-action windows, and prior escalations against the
database, never against anything the AI or the frontend supplies. There is
no frontend or API path that can mark a transaction recovered directly, and
no endpoint — mapped or not — skips the authentication check below.

## Why AI is used only there

Deciding **which** recovery action fits a given failure benefits from
weighing soft, contextual signals — failure category, customer history,
amount, prior attempts — exactly the kind of judgment call an LLM is good
at.

Deciding whether that action is **allowed to run** is a financial safety
question, not a judgment call. It has to be predictable, reproducible, and
auditable: the same inputs must always produce the same decision, and a
human reviewing an incident needs to be able to reconstruct exactly why the
system did what it did. That's why authorization stays in deterministic
code, never a model call — this is a deliberate boundary, not a missing
feature.

## Authentication & authorization

A stateless JWT login (`POST /api/auth/login`) protects every endpoint
except `GET /api/health`, login itself, and the Razorpay webhook (which
keeps its own independent HMAC signature check — see
[AI Judgment](#ai-judgment-what-ai-does-vs-what-ai-cannot-do) above). Two roles: `OPERATOR` (read,
analyze, get AI recommendations, evaluate policy) and `MERCHANT_ADMIN`
(everything `OPERATOR` can, plus authorizing recovery execution). See
[docs/API.md § Authentication](docs/API.md) for the exact request/response
shapes and [docs/ARCHITECTURE.md § Authentication & Authorization](docs/ARCHITECTURE.md)
for the design and its documented limitations (this is a clean
application-level mechanism sized for a buildathon project, not a full
identity platform).

**Demo login** (buildathon judge/reviewer use — intentionally public,
not a real secret; rotate via `DEMO_ADMIN_PASSWORD`/`DEMO_OPERATOR_PASSWORD`
for any non-demo deployment):

| Username | Password | Role |
|---|---|---|
| `merchant.admin` | `RecoverAI-Judge-Admin-2026` | `MERCHANT_ADMIN` |
| `operator` | `RecoverAI-Judge-Operator-2026` | `OPERATOR` |

## Tech stack

**Backend:** Java 17, Spring Boot, Maven, Spring Data JPA, PostgreSQL, Flyway
**Frontend:** React, Vite, TypeScript, Tailwind CSS, React Router, Axios
**AI:** provider-abstracted — a deterministic mock for dev/tests, Anthropic Claude for real inference
**Payments:** Razorpay Test Mode, behind an adapter with a safe mock fallback
**Deployed on:** Render (backend), Vercel (frontend), Neon (PostgreSQL)

## Quick start

**Prerequisites:** Java 17, Maven 3.9+, Node.js 20+, and either PostgreSQL 16 or Docker.

```bash
cp .env.example .env
cd frontend && npm install
```

**Backend** — if you have PostgreSQL/Docker:

```bash
docker compose up -d postgres   # from repo root
cd backend
mvn spring-boot:run
```

**Backend** — without Postgres/Docker, using the built-in H2 fallback:

```bash
# macOS / Linux / Git Bash
cd backend
SPRING_PROFILES_ACTIVE=local DEMO_SEED_ENABLED=true mvn spring-boot:run
```

```powershell
# Windows PowerShell
cd backend
$env:SPRING_PROFILES_ACTIVE = "local"; $env:DEMO_SEED_ENABLED = "true"; mvn spring-boot:run
```

`DEMO_SEED_ENABLED=true` loads a deterministic demo dataset so the
dashboard actually has transactions to show — otherwise you'd start from an
empty database. It's safe to leave on for local dev.

**Frontend:**

```bash
cd frontend
npm run dev
```

Open `http://localhost:5173`. You'll land on a login page — sign in with
the demo credentials above (locally, set `DEMO_SEED_ENABLED=true` and the
`DEMO_ADMIN_PASSWORD`/`DEMO_OPERATOR_PASSWORD` env vars, or seed a user
directly via `AppUserRepository` in a test/console). The `/demo/recovery`
page is the interactive console — pick a scenario and try Analyze Risk,
Get AI Recommendation, Evaluate Policy, and Execute Recovery (requires
`MERCHANT_ADMIN`), or use "Run demo" to walk the whole pipeline for you.
`/transactions` is the general-purpose dashboard — search, filter, sort,
and inspect any transaction in the database, with the same real actions
available on each one.

## Testing

```bash
cd backend && mvn test        # full backend suite — no external services required
cd frontend && npm run build  # type-checks and builds the frontend
```

The backend suite includes a test that spins up a real, temporary
PostgreSQL instance to verify the Flyway migrations and entity mappings
against actual Postgres, not just H2's compatibility mode.

## Measuring real recovered revenue

This is the part most systems get wrong, so it gets its own section.

**Execution success ≠ payment confirmed ≠ revenue recovered.** These are
three different facts, and RecoverAI never collapses them into one:

- Executing a recovery action produces an *execution result* — a provider
  call went through (e.g. a payment link was created). That's it.
- `amountRecovered` stays `0` until a provider confirms the customer
  actually paid.
- A Razorpay webhook's signature is verified over the raw request body
  before a single field of it is trusted.
- The confirmation is correlated to the specific `RecoveryAttempt` that
  created it — never by amount alone, never by a client-supplied id.
- The confirmed amount and currency are independently re-verified against
  what the attempt was actually authorized for.
- Webhook processing is idempotent — a duplicated or replayed delivery can
  never double-count revenue.
- **Only** this verified webhook path can transition a transaction to
  `RECOVERED` or contribute a non-zero figure to recovery metrics.

This is deliberate: it makes it structurally impossible to report a
fabricated recovered-revenue number, even by accident.

**Honest disclosure:** the infrastructure for confirmed-recovery
measurement is fully implemented and tested — signature verification,
correlation, amount/currency checks, idempotency, and an end-to-end test
that drives execution → a genuinely signed webhook → confirmation, over
real HTTP (see [Testing](#testing)). But **this deployed environment has no
real Razorpay Test Mode credentials configured**, so no live webhook has
ever been received here. RecoverAI does not claim a real recovered-money
figure — it reports `₹0.00` confirmed recovered revenue, honestly, because
that is what has actually been confirmed. That claim only changes the day
real Test Mode credentials and a live webhook are configured.

## Real vs. simulated

| Component | Current state |
|---|---|
| Backend | Production deployed (Render) |
| PostgreSQL | Managed Neon PostgreSQL |
| Frontend | Vercel |
| Revenue risk engine | Real, deterministic implementation |
| Policy engine | Real, deterministic implementation |
| AI provider | Mock by default (deterministic, offline); a working Anthropic Claude integration exists, requires an API key to activate |
| Razorpay | Simulation by default; real Test Mode integration exists in code, opt-in via env vars, not exercised against Razorpay's live API in this environment |
| Payment confirmation | Real webhook implementation (signature, correlation, amount/currency, idempotency) — no real webhook received in this environment |
| Recovery metrics | Real backend aggregates, computed only from confirmed recovery |
| Demo dataset | Synthetic, deterministic |
| Real recovered-money figure | Not claimed — reports `₹0.00` honestly, pending a real provider confirmation |

## Known limitations

- Revenue-risk weights and policy thresholds (retry limits, amount
  ceilings, etc.) are illustrative, hand-picked defaults for this
  prototype — not a statistically fitted model or a real merchant's risk
  appetite.
- Only INR is currently supported.
- JWTs are stateless and not revocable before expiry (default 8 hours) —
  there is no server-side session store or logout-everywhere mechanism.
  Appropriate for this project's scope; a real deployment handling
  sensitive data would want short-lived tokens plus a refresh flow.
- Two webhook-rejection counters (invalid signature, malformed payload) in
  `GET /api/observability/metrics` are in-memory and per-instance (reset on
  restart) — the same documented simplification already used for rate
  limiting, since those two failures happen before anything can be
  persisted. Every other webhook/policy/provider count is a real database
  aggregate.

## Project status

- [x] Domain model, database, and synthetic demo dataset
- [x] Deterministic revenue-risk scoring engine
- [x] Deterministic recovery safety/policy engine
- [x] AI recovery agent (recommend-only, policy-gated)
- [x] Razorpay payment adapter (mock + real Test Mode support)
- [x] End-to-end recovery execution pipeline, with idempotency
- [x] Failure-recovery demo scenarios and dashboard
- [x] Production deployment (Render + Vercel + Neon)
- [x] Security/compliance hardening (rate limiting, security headers, PII masking, audit trail)
- [x] Interactive recovery console — every action calls the real backend
- [x] Payment confirmation via a verified, idempotent Razorpay webhook — the only path that can mark a transaction recovered
- [x] General-purpose transaction dashboard (`/transactions`) — filter, search, sort, and inspect any transaction, not just the curated demo scenarios
- [x] Authentication & role-based authorization (JWT, `MERCHANT_ADMIN`/`OPERATOR`) on every endpoint except health, login, and the signature-gated webhook
- [x] Dashboard `recoveryAttemptStatus` filter matches the latest recovery attempt, not any historical one
- [x] Production observability — policy/webhook/provider metrics, structured request-correlated logging
- [x] Concurrency/load smoke tests — concurrent execution across many transactions, concurrent policy evaluation, concurrent dashboard reads, all measured and logged
- [ ] A real Razorpay Test Mode payment actually confirmed end to end (needs live Test Mode credentials, not available in this environment — the intended lifecycle is fully verified with the deterministic mock provider instead, see docs/ARCHITECTURE.md)

## Repository layout

```
recoverai/
├── backend/          Spring Boot API (Java 17, Maven)
│   └── src/main/java/com/recoverai/
│       ├── risk/       deterministic revenue risk engine
│       ├── policy/     deterministic safety/policy engine
│       ├── agent/      AI recovery agent
│       ├── payment/    payment gateway abstraction (mock + Razorpay)
│       ├── execution/  end-to-end recovery execution pipeline
│       ├── webhook/    payment confirmation: signature verification, matching, idempotency
│       ├── demo/       read/aggregation layer for the demo dashboard
│       └── seed/       synthetic dataset generator
├── frontend/          Vite + React + TypeScript console
├── docs/              Deep-dive architecture, API reference, demo script
├── scripts/           Dev convenience scripts
└── .env.example       Environment variable template
```

## Digging deeper

This README stays intentionally short. For the full technical detail —
engine formulas, every endpoint, request/response shapes, deployment
configuration, and the complete list of safety guarantees — see:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how each piece works and why
- [docs/API.md](docs/API.md) — full endpoint reference
- [docs/DEMO.md](docs/DEMO.md) — walkthrough script for the demo scenarios
