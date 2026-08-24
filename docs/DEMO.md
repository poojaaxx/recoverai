# Demo script

Status: as of Phase 8, the full failure-recovery demo walkthrough (risk →
AI → policy → execution → audit, across all 5 named demo transactions) is
real, wired, and viewable in one place — both as a single JSON call and as
a polished frontend page. A general-purpose transaction dashboard (any
transaction, not just the 5 curated ones) is still Phase 9. As of the
deployment phase, this same walkthrough is also runnable as a real,
internet-reachable deployment — see
[README.md § Buildathon Deployment](../README.md#buildathon-deployment) for
the full PostgreSQL/backend/frontend deployment steps, environment
variables, and smoke-test results; this document stays focused on running
and understanding the demo itself, locally or deployed.

## Running the demo

**Locally** — backend (see
[README.md § Running locally](../README.md#running-locally) for full setup):

```bash
cd backend
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run   # or the PostgreSQL profile
```

The demo runs against whatever data is currently seeded. If the 5 named
demo transactions don't exist yet, seed them first (from a test, or by
setting `DEMO_SEED_ENABLED=true` to seed once at startup — see
[README.md § Seed data](../README.md#7-seed-data); `POST /api/demo/seed` as
an on-demand HTTP trigger remains unbuilt — see
[README.md § Known limitations — Phase 8](../README.md#known-limitations--phase-8)).
`DemoDataSeederTest`/any test that autowires `DemoDataSeeder` and calls
`seed()` will also populate them against a local H2/Postgres instance.

**Deployed** — once the backend and frontend are both deployed (see
[README.md § Buildathon Deployment](../README.md#buildathon-deployment)),
the exact same walkthrough works against the real URLs: open the deployed
frontend's `/demo/recovery` route, or call
`GET <backend-url>/api/demo/recovery` directly.

**Frontend:**

```bash
cd frontend
npm run dev
```

Open `http://localhost:5173/demo/recovery`.

## What the demo shows

1. **4 KPI cards** at the top: Revenue at Risk, Potentially Recoverable
   Revenue, Confirmed Revenue Recovered (always ₹0.00 today — see below),
   and Transactions at Risk.
2. **A visual pipeline diagram**: Payment Failure → Risk Detection → AI
   Recommendation → Safety Policy → ALLOW? → (yes) Execute Payment →
   Confirmation → Audit, or (no) Escalate/Block/Stop — captioned "AI
   recommends. Policy authorizes."
3. **5 scenario cards**, one per named demo transaction, each showing
   amount, risk badge, AI recommendation, policy decision badge, and an
   outcome badge (`SUCCESS` / `FAILED` / `NOT EXECUTED` / `PENDING
   CONFIRMATION`).
4. **A detail panel** for the selected scenario: full risk analysis
   (score, level, factors, reason), AI recommendation (action, confidence,
   rationale), policy decision (decision, reason, human-approval flag),
   execution result (provider, simulated flag, amount recovered, failure
   code), a plain-language safety explanation, and the real audit
   timeline.

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
  (`simulated=true`, `amountRecovered=0.00`). Shown as `PENDING
  CONFIRMATION`, never `SUCCESS` — the provider call ran, but nothing
  confirms the customer actually paid.
- **`demo-high-value`** — AI recommends `RETRY_PAYMENT`, but the amount
  (₹47,500) exceeds the autonomous recovery limit, so policy overrides to
  `ESCALATE` (`requiresHumanApproval=true`) → not executed, zero gateway
  calls. This is the scenario that most directly demonstrates **"AI
  recommends. Policy authorizes."** — the recommendation and the final
  decision are shown side by side, and they differ.
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
[README.md § Recovery Execution Pipeline](../README.md#recovery-execution-pipeline-phase-7).

## Repeatability

Running the demo more than once is safe by construction — no reset step is
required. Re-analyzing risk is an idempotent upsert, and a repeated
execution attempt is naturally re-blocked by Phase 4's existing
`DUPLICATE_ACTION` policy check (the same mechanism that already protects
Phase 7's execution endpoint), so no scenario can accumulate extra
`RecoveryAttempt` rows or drift into a contradictory state just from being
viewed again. See
[README.md § Failure-Recovery Demo](../README.md#failure-recovery-demo-phase-8)
for why no `POST /api/demo/reset` was needed.

## Still to come

- How to seed the demo dataset over HTTP (`POST /api/demo/seed`) rather
  than only via a test — Phase 8's spec did not require this endpoint, and
  it remains planned.
- A general-purpose dashboard covering any transaction, not just the 5
  curated demo ones (Phase 9).
- Batch execution and a real, measured "₹X recovered" figure across many
  transactions (a later phase, once a provider-confirmation mechanism
  exists).
