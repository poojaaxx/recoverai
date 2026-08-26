# Demo script

Status: as of Phase 8, the full failure-recovery demo walkthrough (risk →
AI → policy → execution → audit, across all 5 named demo transactions) is
real, wired, and viewable in one place — both as a single JSON call and as
a polished frontend page. A general-purpose transaction dashboard (any
transaction, not just the 5 curated ones) is still a future phase. As of
Phase 9, this same walkthrough is live on the internet — see
[README.md § Live deployment](../README.md) for
the real URLs, provider setup, environment variables, and what was
verified against the live system; this document stays focused on running
and understanding the demo itself, locally or deployed. Phase 10 added a
security/compliance hardening pass (rate limiting, security headers,
masked customer email, a global error-handling safety net) with no change
to the demo flow itself — see
[README.md § Audit, Compliance & Production Hardening](../README.md).
One practical note if a demo hits it: the evaluation/execution endpoints
now return `429` if the same client sends more than 20 requests in 60
seconds — normal clicking through the demo page stays well under that.
Phase 12 added real payment confirmation via a verified Razorpay webhook
(`POST /api/webhooks/razorpay`) and portfolio metrics
(`GET /api/recovery/metrics`) — see
[README.md § What's real vs. simulated](../README.md). This demo runs
against the default mock payment provider, so every execution stays
`paymentConfirmationStatus=NOT_CONFIRMED` forever (no real webhook can ever
arrive for a mock-generated reference) — see "The five scenarios" below.
The production readiness phase added a required login step (see
"Signing in" below) and, separately, verified the complete intended
confirmation lifecycle end to end with the mock provider — see "How to
verify the payment confirmation flow" below.

## Signing in

Every page except the login page itself now requires signing in
(`POST /api/auth/login`) — see
[README.md § Authentication & authorization](../README.md) for the demo
credentials. `MERCHANT_ADMIN` can do everything shown below, including
Execute Recovery; `OPERATOR` can do everything except execute (a 403 with
a plain-language reason if attempted). Signing in issues a JWT stored in
the browser only for that session — nothing about who is signed in changes
what the AI recommends, what the policy engine decides, or what gets
executed; it only changes whether the request is let through at all.

## Running the demo

**Locally** — backend (see
[README.md § Running locally](../README.md) for full setup):

```bash
cd backend
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run   # or the PostgreSQL profile
```

The demo runs against whatever data is currently seeded. If the 5 named
demo transactions don't exist yet, seed them first (from a test, or by
setting `DEMO_SEED_ENABLED=true` to seed once at startup — see
[README.md § Seed data](../README.md); `POST /api/demo/seed` as
an on-demand HTTP trigger remains unbuilt — see
[README.md § Known limitations — Phase 8](../README.md)).
`DemoDataSeederTest`/any test that autowires `DemoDataSeeder` and calls
`seed()` will also populate them against a local H2/Postgres instance.

**Deployed** — the application is live; the exact same walkthrough works
against these real URLs (see
[README.md § Live deployment](../README.md) for
the full provider/environment/verification record):

- Frontend demo page: https://recoverai-bay.vercel.app/demo/recovery
- Backend directly: https://recoverai-xrky.onrender.com/api/demo/recovery
- Health check: https://recoverai-xrky.onrender.com/api/health

The backend is on Render's free tier, which spins down when idle — the
first request after a period of inactivity can take up to a couple of
minutes to respond while it cold-starts. This is a platform characteristic,
not an application issue.

**Frontend:**

```bash
cd frontend
npm run dev
```

Open `http://localhost:5173/demo/recovery`. From there, "All transactions →"
links to `/transactions` — the general-purpose dashboard (Phase 13) over
every transaction in the database, not just these 5 curated scenarios. See
[docs/ARCHITECTURE.md § General Transaction Dashboard](ARCHITECTURE.md)
and [docs/API.md](API.md) for its filters, search, sort, and detail view.

## What the demo shows

1. **5 KPI cards** at the top: Revenue at Risk, Potentially Recoverable
   Revenue, Provider Executions (a gateway call, not a confirmed payment),
   Confirmed Revenue Recovered (₹0.00 until a real or signed-test webhook
   confirms a payment — see below), and Transactions at Risk (with filter
   chips: All/Allowed/Escalated/Blocked/Stopped). Next to "Refresh
   dashboard" in the header, a **"Reset demo data"** button
   (`MERCHANT_ADMIN` only, confirms before running) restores the entire
   dataset to its original deterministic state via
   `POST /api/demo/recovery/reset` — see [Repeatability](#repeatability)
   below. Below the portfolio actions panel, a **portfolio metrics panel**
   (`GET /api/recovery/metrics`) shows the same figures computed across
   every recovery attempt ever made, not just these 5 scenarios, plus
   Recovery Attempts, Confirmed Recoveries, and Pending Confirmation
   amount.
2. **A visual pipeline diagram**: Payment Failure → Risk Detection → AI
   Recommendation → Safety Policy → ALLOW? → (yes) Execute Payment →
   Confirmation → Audit, or (no) Escalate/Block/Stop — captioned "AI
   recommends. Policy authorizes."
3. **5 scenario cards**, one per named demo transaction, each showing
   amount, risk badge, AI recommendation, policy decision badge, and an
   outcome badge (`SUCCESS` / `FAILED` / `NOT EXECUTED` / `PENDING
   CONFIRMATION`).
4. **An interactive operations panel** for the selected scenario (Phase
   11) — not a static snapshot. Each section has its own real, backend-
   backed action:
   - **Analyze risk** / **Re-analyze risk** → `POST /api/revenue-risk/analyze/{id}`
   - **Get AI recommendation** / **Re-evaluate** → `POST /api/recovery-agent/evaluate/{id}`
     (this single call also returns the policy's decision on that
     recommendation - "AI recommends, policy authorizes" from one real
     round trip)
   - **Evaluate policy** → `POST /api/recovery-policy/evaluate/{id}`, a
     standalone re-check of the current recommended action, showing every
     individual policy check (`RETRY_LIMIT`, `AMOUNT_LIMIT`,
     `DUPLICATE_ACTION`, ...) pass/fail
   - **Execute recovery** → `POST /api/recovery/{id}/execute`, enabled
     only when the latest known policy decision is `ALLOW` and nothing
     has been executed yet for this transaction - disabled with a visible
     reason otherwise (`BLOCK`/`ESCALATE`/`STOP` each show their own
     plain-language banner from the real backend reason). The result
     panel shows **Execution** (status, provider, failure code) and
     **Payment** (confirmation badge: `NOT CONFIRMED`/`CONFIRMED`/
     `REJECTED`, confirmed amount, provider payment id) as two separate
     facts, plus a `SIMULATION — NO REAL MONEY MOVED` banner whenever the
     execution used the mock provider - see [Payment confirmation](API.md)
     in docs/API.md.
   - **Refresh audit** → `GET /api/audit/{id}` (Phase 11, read-only, no
     side effects - see [docs/API.md § Audit Trail](API.md#audit-trail))
   - **▶ Run demo** — a guided version of the same steps for the selected
     scenario, with a visible progress indicator (Analyzing risk → Getting
     AI recommendation → Ready for execution/Blocked → Executing →
     Completed) that always stops and waits for an explicit click on
     "Execute recovery" rather than executing automatically

   None of these buttons compute anything client-side - every value shown
   (risk score, confidence, policy reason, execution result) is the real
   backend response, not a local calculation or a fabricated success
   state. The frontend cannot supply its own amount, currency, or action
   to the execution endpoint (it takes only a transaction id), cannot
   force `ALLOW`, and has no bypass for `BLOCK`/`ESCALATE`/`STOP`.

This can also be called directly, without the frontend, via
`GET /api/demo/recovery` (all 5 scenarios + aggregate metrics) or
`GET /api/demo/recovery/{externalTransactionId}` (one scenario in full
detail) — see [docs/API.md § Failure-Recovery Demo](API.md#failure-recovery-demo-phase-8)
for exact response shapes.

## The five scenarios

Each of the 5 named demo transactions demonstrates a different corner of
the safety architecture:

- **`demo-easy-recovery`** — AI recommends `RETRY_PAYMENT` → policy
  `ALLOW` → **executed** through the mock `PaymentGateway`
  (`simulated=true`, `amountRecovered=0.00`,
  `paymentConfirmationStatus=NOT_CONFIRMED`). Shown as `PENDING
  CONFIRMATION`, never `SUCCESS` — the provider call ran, but no verified
  webhook has confirmed the customer actually paid (and none ever will,
  under the default mock provider).
- **`demo-high-value`** — the AI recommends a customer-facing action
  (under the deterministic mock provider, `RETRY_PAYMENT`; the live Groq
  deployment currently recommends `CREATE_PAYMENT_LINK`, reasoning about
  the insufficient-funds failure code — the exact action depends on which
  provider is configured, since a real model call is genuinely free to
  choose), but the amount (₹47,500) exceeds the autonomous recovery limit
  regardless, so policy overrides to `ESCALATE`
  (`requiresHumanApproval=true`) → not executed, zero gateway calls. This
  is the scenario that most directly demonstrates **"AI recommends.
  Policy authorizes."** — the recommendation and the final decision are
  shown side by side, and they differ.
- **`demo-repeated-failure`** — already `STOPPED` after exhausting its
  automated retry budget → policy `STOP` again → not executed. Demonstrates
  bounded automation: the system does not retry forever.
- **`demo-successful-recovery`** — already `RECOVERED` → policy `BLOCK`
  (nothing left to do) → not executed.
- **`demo-retry-escalation`** — already `ESCALATED` and awaiting manual
  review → policy `ESCALATE` again → not executed.

Every case demonstrates that the **policy engine, not the AI**, decides
what happens — and that even the one case that does execute never claims
money was recovered. "Payment link created" is not "money recovered" —
see
[README.md § Recovery Execution Pipeline](../README.md).

## How to verify the payment confirmation flow

No real Razorpay Test Mode credentials are configured in this
environment, so this demo cannot show a real webhook arriving from
Razorpay's servers. What it *can* show — and what actually proves the
confirmation logic is real, not decorative — is the same code path a real
webhook would hit, driven by a genuinely HMAC-signed request instead of a
live one:

1. Run `mvn test -Dtest=EndToEndRecoveryConfirmationTest` from `backend/`
   (or read `backend/src/test/java/com/recoverai/webhook/EndToEndRecoveryConfirmationTest.java`
   directly). It executes a transaction through the real pipeline (AI →
   policy ALLOW → mock gateway), takes the `providerReference` the
   execution actually produced, builds a payload referencing that exact
   reference, signs it with `RazorpayWebhookSignature.sign(...)` using the
   configured webhook secret, and posts it to the real
   `POST /api/webhooks/razorpay` endpoint.
2. The test asserts, in order: `amountRecovered` is `0.00` and the
   transaction is still `FAILED` immediately after execution (execution
   success alone never confirms anything) → the signed webhook is accepted
   (`200 {"status":"CONFIRMED"}`) → the `RecoveryAttempt` becomes
   `paymentConfirmationStatus=CONFIRMED` with `amountRecovered > 0` → the
   `Transaction` becomes `RECOVERED` → `GET /api/recovery/metrics`
   reflects the new confirmed count/revenue → the audit trail contains
   both `RECOVERY_EXECUTION_COMPLETED` and `PAYMENT_RECOVERY_CONFIRMED`.
3. To see the rejection paths (wrong signature, amount mismatch, currency
   mismatch, unknown reference, duplicate delivery, already-confirmed
   attempt), see `PaymentConfirmationServiceTest` — every one of those is
   exercised against the real endpoint with a real (mis-signed or
   mismatched, as appropriate) request, never a parallel unsigned bypass.

This is the honest claim: the confirmation flow is real, tested, signature
-verified code, exercised end to end — not a real Razorpay Test Mode
payment, which would require live credentials this environment doesn't
have.

## Repeatability

Running the 5 named scenarios more than once is safe by construction — no
reset step is required for them specifically. Re-analyzing risk is an
idempotent upsert, and a repeated execution attempt is naturally re-blocked
by Phase 4's existing `DUPLICATE_ACTION` policy check (the same mechanism
that already protects Phase 7's execution endpoint), so no scenario can
accumulate extra `RecoveryAttempt` rows or drift into a contradictory state
just from being viewed again.

For the *wider* dataset — after batch executions, webhook confirmations, or
other portfolio-wide testing — `POST /api/demo/recovery/reset`
(`MERCHANT_ADMIN`, development/demo-only) restores everything (transactions,
customers, risk rows, recovery attempts, webhook events, audit records) to
the original deterministic seed, via the same routine `DemoSeedRunner` runs
once at startup. It never touches `AppUser` login rows, so resetting can
never log out the admin who called it, and it's idempotent — calling it
twice in a row lands on the exact same shape both times. It's also wired
into the frontend: a **"Reset demo data"** button on the `/demo/recovery`
page header (visible to any signed-in user, but the server still enforces
`MERCHANT_ADMIN` — an `OPERATOR` gets the real 403 message inline) calls
this endpoint, shows the resulting counts, and refreshes the dashboard.
Requires `DEMO_SEED_ENABLED=true` and refuses (409) if
`RAZORPAY_ENABLED=true` — see
[docs/API.md § `POST /api/demo/recovery/reset`](API.md#post-apidemorecoveryreset-merchant_admin-only).

## Still to come

- A real Razorpay Test Mode payment actually confirmed end to end — the
  confirmation flow (Phase 12) is real, tested code, but no live Razorpay
  Test Mode credentials have been configured in this environment, so no
  real webhook has ever been received here. What *is* now available: a
  judge-safe "Confirm via signed webhook (TEST/SIMULATION)" button in the
  demo console (`POST /api/demo/recovery/confirm-test-payment/{id}`) that
  drives a real signed payload through the exact same confirmation code a
  genuine webhook would hit — see [README.md § Measuring recovered
  revenue](../README.md) and [docs/API.md](API.md) for exactly what it
  does and doesn't prove.
