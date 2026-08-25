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
     plain-language banner from the real backend reason)
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
[README.md § Recovery Execution Pipeline](../README.md).

## Repeatability

Running the demo more than once is safe by construction — no reset step is
required. Re-analyzing risk is an idempotent upsert, and a repeated
execution attempt is naturally re-blocked by Phase 4's existing
`DUPLICATE_ACTION` policy check (the same mechanism that already protects
Phase 7's execution endpoint), so no scenario can accumulate extra
`RecoveryAttempt` rows or drift into a contradictory state just from being
viewed again. See
[README.md § Failure-Recovery Demo](../README.md)
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
