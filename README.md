# RecoverAI

**AI Revenue Recovery Agent** — built for the Razorpay Buildathon, Track 03.

> Detect revenue at risk. Decide the right intervention. Recover it safely.

Payments fail for reasons that have nothing to do with a customer's intent
to pay — an expired card, a bank's risk rule, a temporary network blip.
Merchants leave this revenue on the table because reacting to every failed
payment individually is expensive, and blunt "retry everything" automation
annoys customers and trips fraud rules.

RecoverAI watches a merchant's transaction stream, scores which failed
payments are worth chasing, asks an AI agent to diagnose *why* a payment
failed and recommend one action, checks that action against a deterministic
safety policy, and — only if authorized — executes it. Every decision is
logged, so the result is a system you can actually audit, not a demo
gimmick.

**Live app:** https://recoverai-bay.vercel.app/demo/recovery
**API health check:** https://recoverai-xrky.onrender.com/api/health

## How it works

```
Transaction data
   -> Risk scoring (deterministic)
   -> AI diagnosis + recommendation
   -> Safety policy check (deterministic — the actual gatekeeper)
   -> Recovery action execution (Razorpay Test Mode, or a simulation adapter)
   -> Provider webhook confirms the payment (signature-verified, idempotent)
   -> Audit trail + metrics
```

The AI only ever *recommends*. It has no access to payment APIs. A separate,
deterministic policy engine is the one thing that decides whether an action
is actually allowed to run — see [Safety](#safety) below.

**Execution success is not the same thing as confirmed recovery.** Running
a payment action means a provider call went through (e.g. a payment link was
created) — it does not mean the customer paid. Only a verified, signature
-checked webhook from the provider can confirm that, and only that
confirmation ever marks a transaction "recovered" or reports non-zero
recovered revenue.

## Safety, in plain terms

These are hard boundaries in the code, not just intentions:

- The AI recommends an action; it never authorizes or executes one.
- A deterministic policy engine (`RecoveryPolicyService`) is the only thing
  that can approve a money-moving action — it checks retry limits, amount
  ceilings, duplicate-action windows, and prior escalations against the
  database, never against anything the AI or the frontend supplies.
- Real payments only ever happen through Razorpay's **Test Mode**, and only
  when explicitly turned on (two separate opt-ins). By default, every
  execution runs through a mock gateway that's honest about being simulated.
- Nothing in this system marks a transaction "recovered" or reports revenue
  collected unless a payment was genuinely confirmed by a verified provider
  webhook. Simulated runs stay at ₹0 recovered — always.
- The webhook endpoint verifies the provider's signature over the raw
  request body before trusting a single field of it, matches a confirmation
  to a specific recovery attempt (never by amount alone or a client
  -supplied id), independently re-checks the paid amount/currency, and is
  idempotent — a duplicated or concurrently-replayed delivery can never
  double-count revenue. There is no frontend or API path that can mark a
  transaction recovered directly.
- The frontend never decides anything — it's a thin client over these same
  backend rules, so what you click can't skip a safety check the API
  wouldn't already enforce.

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

Open `http://localhost:5173`. The `/demo/recovery` page is the interactive
console — pick a scenario and try Analyze Risk, Get AI Recommendation,
Evaluate Policy, and Execute Recovery, or use "Run demo" to walk the whole
pipeline for you.

## Testing

```bash
cd backend && mvn test        # full backend suite — no external services required
cd frontend && npm run build  # type-checks and builds the frontend
```

The backend suite includes a test that spins up a real, temporary
PostgreSQL instance to verify the Flyway migrations and entity mappings
against actual Postgres, not just H2's compatibility mode.

## What's real vs. simulated

- Risk scoring and the safety policy engine are real, deterministic code —
  every score and decision is computed from actual transaction data, not
  hardcoded.
- The AI recommendation is genuinely generated by a real provider call
  (mock by default; a working Anthropic integration exists but requires an
  API key to activate).
- Payment execution defaults to a mock gateway that's explicit about being
  simulated. A real Razorpay Test Mode integration exists in code but
  requires explicit configuration to turn on, and has not been exercised
  against Razorpay's live API in this environment.
- The payment-confirmation webhook (`POST /api/webhooks/razorpay`) is real,
  tested code — signature verification, correlation, amount/currency
  checks, and idempotency are all exercised by real signed test fixtures
  (see [Testing](#testing)). No transaction has ever been marked
  "recovered," and no recovered-revenue figure has ever been non-zero,
  outside of a genuinely confirmed payment — which nothing available today
  (mock provider, or Razorpay left unconfigured) can produce. **No real
  Razorpay Test Mode webhook has been received in this environment** — no
  real payment has ever been confirmed here; that claim is made only for
  the day real Test Mode credentials and a live webhook are configured.

## Known limitations

- Revenue-risk weights and policy thresholds (retry limits, amount
  ceilings, etc.) are illustrative, hand-picked defaults for this
  prototype — not a statistically fitted model or a real merchant's risk
  appetite.
- The Anthropic and Razorpay integrations are real, defensively-written
  code, but neither has been run against its live third-party API in this
  environment (no credentials available) — both fail closed to a safe
  fallback rather than break or bypass anything.
- Only INR is currently supported.
- There's no general-purpose "any transaction" dashboard yet — the
  interactive console works over five curated demo scenarios plus
  whole-portfolio batch actions.

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
- [ ] General-purpose transaction dashboard (beyond the curated demo scenarios)
- [ ] A real Razorpay Test Mode payment actually confirmed end to end (needs live Test Mode credentials, not available in this environment)

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
