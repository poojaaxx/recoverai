# RecoverAI

An AI-assisted revenue recovery agent for failed payments, built for the Razorpay Buildathon, Track 03.

## The problem

Every payment platform loses money to failed payments — an expired card, a bank declining a routine charge, a network blip, a customer who just didn't have funds that day. A lot of that revenue is actually recoverable if someone follows up the right way. Most systems either do nothing about it, or blindly retry every failed payment, which annoys customers and can trip fraud rules.

Track 03 is about recovering that revenue intelligently instead of blindly. The interesting part isn't "can an AI suggest a retry" — it's building a system where an AI can be genuinely useful for judgment calls (why did this fail, what's worth trying) without ever being the thing that's allowed to move money.

That's the whole premise behind RecoverAI: detect which failures are worth chasing, get an AI's read on what to do, but let a deterministic rules engine decide what's actually allowed to run.

## What I built

The core loop looks like this:

```
Transaction → Risk scoring → AI recommendation → Policy check → Recovery execution → Payment confirmation → Measured revenue
```

A failed payment gets scored for how much revenue is at risk and how recoverable it looks. An AI agent looks at that context and recommends one action (retry, escalate, stop, whatever fits). That recommendation then goes through a separate, deterministic policy engine that actually decides whether it's allowed to run — checking retry limits, amount limits, duplicate attempts, and so on. Only if the policy says ALLOW does anything touch the payment provider. And even then, the system doesn't count the money as recovered until a signed webhook from the provider confirms the customer actually paid.

The part I care most about here: the AI never executes a payment. It only ever recommends.

## How the AI is used

The AI agent gets the transaction, the customer's payment history, and the risk score, and picks one recovery action along with a confidence score and a short rationale. That's it — it has no access to the payment gateway and can't write a recovery attempt to the database.

Every recommendation still has to pass through the policy engine before anything happens:

- AI recommends `RETRY_PAYMENT` → policy checks the amount and retry history → says **ALLOW** → execution proceeds.
- AI recommends `RETRY_PAYMENT` on a high-value transaction → policy says **ESCALATE** instead, because it's over the autonomous limit → nothing executes, a human has to look at it.

So the AI is useful for the "what should we try" judgment call, and the policy engine is the one thing that gets to say yes to actually spending a provider call on it. I kept it this way on purpose — authorization needs to be predictable and explainable, and an LLM call isn't a good fit for that.

Worth being precise about which "AI" is actually running. By default it's a deterministic decision engine, not a live model call — the UI says so explicitly ("Deterministic AI simulation — no external LLM configured") rather than implying more than is true. There are two real, live LLM integrations behind the same interface, genuinely wired to make an API call: Anthropic Claude (`AI_PROVIDER=anthropic` + `ANTHROPIC_API_KEY`) and Groq's OpenAI-compatible API (`AI_PROVIDER=groq` + `GROQ_API_KEY`, model set via `GROQ_MODEL`) — see `.env.example`. **The deployed backend now runs with `AI_PROVIDER=groq` and a real `GROQ_API_KEY` configured** — every recommendation you see on the live demo is a genuine Groq inference call (model `openai/gpt-oss-120b`), not the mock. The observability panel shows this directly ("Groq — openai/gpt-oss-120b"), and it's independently verifiable live: `GET /api/observability/metrics` reports `"aiProviderMode":"groq"`. When neither key is configured (e.g. running locally without one), the same UI falls back to saying "Deterministic AI simulation" instead — never more than one provider active at once, and never implying a live call happened when it didn't. Both keys are read only from their server-side environment variable: never logged, never returned by any API response, never stored in the database, and never reachable from the frontend bundle (Vite only ever exposes `VITE_API_BASE_URL`, nothing AI-related). If the configured provider's call actually fails (network error, timeout, malformed output), the observability panel still honestly shows which provider is *configured* — that never changes based on one call's outcome — while the specific recommendation that failed is separately labeled "AI unavailable — escalated automatically" rather than being attributed to a provider that didn't actually produce it.

I found and fixed a real bug in this integration while adding direct test coverage for it: the Anthropic provider built its own JSON mapper without the module Java's `Instant` type needs, so serializing the recovery context to send to Claude would have thrown before any network request was made — a real API key would never actually have worked. Nobody had ever exercised that code path end-to-end, mock-vs-live tests only ever went through the mock provider. Fixed by registering the missing module; now covered by a dedicated test class that drives the provider's real HTTP/parsing logic against a fake `WebClient` (no network, no key needed) — malformed JSON, an empty response, an unsupported action, a non-2xx status, and a network failure all correctly fail closed to the same policy-gated fallback path.

Groq was added the same way, deliberately reusing that exact structure rather than inventing a new one: same interface, same request/response validation, same fail-closed error handling, same "recommend-only, no repository/gateway/policy access" contract — the only real difference is the wire format (Groq's OpenAI-compatible `chat/completions` shape vs. Anthropic's `messages` shape), and it ships with the JSON-mapper fix already applied. A Groq-flavored recommendation is put through the exact same policy-override test the other two providers get, proving the AI-vs-policy authorization boundary doesn't care which provider produced the recommendation.

The Groq integration then broke live, for a reason worth being honest about: Groq deprecated the model I'd defaulted to (`llama-3.3-70b-versatile`) on 2026-08-16, and every request to a removed model id gets back a bare `404 Not Found` from Groq rather than a descriptive error — everything else about the request (URL, method, headers, JSON shape) was already correct, which is exactly why the fail-closed handling worked as designed: it degraded to the safe ESCALATE fallback instead of breaking anything. Fixed by switching the default to `openai/gpt-oss-120b`, Groq's own recommended replacement, and adding a test that captures the actual outgoing request body (not just the response) to prove the configured model genuinely reaches Groq rather than trusting a hardcoded value. Worth knowing if this happens again: check `console.groq.com/docs/deprecations` — Groq retires specific model ids on its own schedule, independent of anything in this codebase.

## The recovery flow

```
Payment fails
   |
   v
Risk scoring (how much is at risk, how recoverable)
   |
   v
AI recommendation (one action + confidence + reason)
   |
   v
Policy check (ALLOW / BLOCK / ESCALATE / STOP)
   |
   v
Execution — only if ALLOW
   |
   v
Payment provider call (Razorpay Test Mode, or a mock)
   |
   v
Webhook confirmation (signature verified)
   |
   v
Revenue counted as recovered — only now
```

Each stage is a real, separately-tested piece of the backend, not just a diagram — see the Build Quality section below.

## What happens when things go wrong

This was most of the actual engineering work, honestly. A few examples:

If a transaction already hit its retry limit, the policy returns STOP and nothing gets called — no matter what the AI suggests. If the amount is above the autonomous limit, it escalates for human approval instead of running automatically. If the provider itself declines or times out, that's recorded as a failed attempt, not a recovered one, and the transaction stays failed. Two execution requests for the same transaction firing at once (which I tested with actual concurrent threads) resolve to exactly one provider call, thanks to a database-level idempotency constraint — the second request just gets back the same result instead of double-charging. Webhook deliveries are idempotent the same way, so a replayed or duplicated webhook can't double-count revenue. And if the AI provider itself fails or returns something malformed, the system falls back to escalating rather than guessing.

A couple of things I added after the first pass, once I thought harder about what "safe" actually requires: a cooldown between recovery actions on the same transaction (off by default so it doesn't get in the way of the demo, but real and tested), and a proper escalation review — a merchant admin can approve or reject an escalated transaction, and approving doesn't just wave it through. It re-runs the entire AI-and-policy pipeline fresh; if the amount is still over the limit, it escalates right back and nothing executes. I also stopped letting `SEND_RECOVERY_REMINDER` be a silent no-op — it's now a real, auditable, non-payment action that counts toward the same retry limits as everything else, instead of quietly doing nothing while looking like it did something.

None of this is exotic — it's mostly "don't trust anything twice, and fail toward the safe option."

I later added two more of these safety boundaries. First, a simple customer consent flag — if a customer has opted out of recovery contact, the policy engine blocks every autonomous action for them (retry, payment link, reminder — all of it), before the AI's recommendation is even considered. It's a deterministic policy check like any other, not something the frontend decides. Second, a bounded batch execution endpoint: a merchant admin can select a handful of transactions and execute them together, but the server still reloads every one from the database and re-runs the entire AI-and-policy pipeline fresh for each, individually, immediately before executing it. Nothing about "being in a batch" skips a single safety check — it's really just the same single-transaction pipeline called in a loop, with a hard cap on how many transactions and how much money one batch request can touch, enforced server-side and never exceeded even partially.

## Measuring recovered revenue

This is the part I was most careful about, because it's easy to get wrong (or fake).

Execution success is not the same as payment success. A recovery action succeeding just means a provider call went through — e.g. a payment link got created. It does not mean the customer paid. The transaction only gets marked `RECOVERED`, and `amountRecovered` only becomes non-zero, after a Razorpay webhook arrives, its signature is verified, and the confirmed amount/currency are checked against what was actually authorized.

I'm being upfront about where this currently stands: **the deployed environment doesn't have real Razorpay Test Mode credentials configured**, so it runs on the mock/simulation gateway. That means confirmed recovered revenue in this environment is genuinely ₹0.00 — always has been. The confirmation pipeline itself (signature verification, correlation, idempotency, the whole thing) is implemented and covered by tests that drive a real signed webhook through the real endpoint. It's just never had a real payment to confirm. I'd rather say that plainly than dress up a number that isn't real.

Since "trust me, the code works" isn't very convincing on its own, there's a button for this in the demo console too: once a recovery has executed against the mock gateway, "Confirm via signed webhook (TEST/SIMULATION)" builds a real payment_link.paid payload from that attempt's own amount and reference, signs it with the actual configured webhook secret, and sends it through the exact same `PaymentConfirmationService` code a real Razorpay webhook would hit — not a shortcut, not a different endpoint. You can watch the number go from ₹0.00 to non-zero and then go check the audit trail for proof it went through real verification. It's gated so it only ever works in demo mode, only against a mock-provider execution, and every result is labeled TEST/SIMULATION so it's never confused with a real payment.

## Demo

Live app: **https://recoverai-bay.vercel.app/demo/recovery**

You'll be asked to log in first — use `merchant.admin` / `RecoverAI-Judge-Admin-2026` for full access (including executing a recovery), or `operator` / `RecoverAI-Judge-Operator-2026` for a read-only view. Login issues a stateless JWT; each account also carries a server-side token version, so `POST /api/auth/refresh` can re-issue a token from current DB state (while the current one is still valid) and `POST /api/auth/logout` (wired to the "Log out" button) instantly revokes every previously issued token for that account, not just the one in hand — a real gap this pass closed, since the JWT scheme originally had no revocation mechanism at all.

The `/demo/recovery` console walks through 5 named scenarios — an easy recovery that gets ALLOWed, a high-value transaction that gets ESCALATEd instead of auto-retried, a transaction that already hit its STOP limit, one that's already recovered, and one already escalated. Each button (Analyze Risk, Get AI Recommendation, Evaluate Policy, Execute Recovery) calls the real backend, nothing is precomputed. There's also a `/transactions` dashboard for browsing and filtering any transaction in the database, not just the 5 curated ones, and a `/audit` page showing the same kind of audit trail across the whole portfolio instead of one transaction at a time.

If a transaction is escalated, its detail page shows an "Escalation review" panel — a merchant admin can approve it (which re-runs the whole AI-and-policy check fresh rather than just waving it through) or reject it (which just records that a human looked at it and said no). The recovery console also has a portfolio-wide escalation queue (every currently-escalated transaction, same approve/reject actions) and a bounded batch recovery panel — pick a handful of failed transactions, review the selection and its estimated total, and execute; the response shows exactly what happened to each one, not just one aggregate success flag.

## Tech stack

**Backend:** Java 17, Spring Boot, Spring Data JPA, PostgreSQL (Neon), Flyway, Maven
**Frontend:** React, TypeScript, Vite
**AI:** deterministic mock provider by default; Anthropic Claude and Groq integrations exist behind the same interface — **the deployed backend runs live on Groq (`openai/gpt-oss-120b`)**
**Payments:** Razorpay adapter behind a `PaymentGateway` interface — mock by default, real Test Mode is an opt-in config flag
**Deployment:** Render (backend), Vercel (frontend), Neon (PostgreSQL)

## Build quality

404 backend tests passing (`mvn test`), frontend builds clean (`npm run build`). That includes a test that runs the actual Flyway migrations against a real, temporary PostgreSQL instance rather than just H2, concurrency tests that hit the execution and webhook endpoints with real parallel threads, and webhook tests that go through real signature verification rather than a fake bypass. It's all deployed and reachable live, not just running locally.

## A failure I actually hit

At one point I edited a Flyway migration file after it had already been applied to the production database — you're not supposed to do that, and Flyway noticed immediately. Every deploy after that started failing with a checksum mismatch (the site itself stayed up, since Render just kept serving the last working build). I fixed it properly instead of hacking around it: added a new forward migration to carry the actual change, and used Flyway's own repair mechanism to fix the checksum bookkeeping without touching anything already applied. Deploys went back to normal after that. I'm leaving this in the README because it's a real thing that happened during a real deployment, not just the happy path.

## Project structure

```
recoverai/
├── backend/    Spring Boot API
│   └── src/main/java/com/recoverai/
│       ├── risk/       revenue risk scoring
│       ├── policy/     deterministic authorization
│       ├── agent/      AI recommendation
│       ├── payment/    payment gateway adapter
│       ├── execution/  ties risk + AI + policy + payment together
│       └── webhook/    payment confirmation
├── frontend/   React + TypeScript console
├── docs/       deeper architecture/API/demo notes
└── .env.example
```

## Running locally

```bash
cp .env.example .env
cd backend && SPRING_PROFILES_ACTIVE=local DEMO_SEED_ENABLED=true mvn spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

That runs the backend against an in-memory H2 database with demo data seeded, no PostgreSQL setup required. Log in at `http://localhost:5173` with `merchant.admin` / `RecoverAI-Judge-Admin-2026` (or set your own `DEMO_ADMIN_PASSWORD`/`DEMO_OPERATOR_PASSWORD` first). See `.env.example` for every config option, including how to point it at real PostgreSQL or turn on the Anthropic/Groq/Razorpay integrations.

### Environment variables

Everything below is read server-side only — none of it is ever exposed to the frontend bundle, logged, or returned by any API response. Full detail and defaults live in `.env.example`.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local Postgres | Required outside the `local` (H2) profile |
| `SPRING_PROFILES_ACTIVE` | unset (Postgres) | `local` = offline H2, dev-only; `prod` = stricter CORS/logging |
| `FRONTEND_URL` / `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Which origin(s) may call the API |
| `VITE_API_BASE_URL` | `http://localhost:8080` | The only secret-free `VITE_*` variable — frontend build time |
| `AUTH_JWT_SECRET` | insecure local placeholder | **Must** be set to a real random value in any real deployment |
| `DEMO_ADMIN_USERNAME` / `DEMO_ADMIN_PASSWORD` / `DEMO_OPERATOR_USERNAME` / `DEMO_OPERATOR_PASSWORD` | `merchant.admin` / unset / `operator` / unset | Demo login accounts — only seeded when `DEMO_SEED_ENABLED=true` |
| `AI_PROVIDER` | `mock` | `mock` (no key needed) \| `anthropic` (+ `ANTHROPIC_API_KEY`) \| `groq` (+ `GROQ_API_KEY`, `GROQ_MODEL`) |
| `RAZORPAY_ENABLED` / `RAZORPAY_MODE` | `false` / `simulation` | Both must be `true` / `test` to use the real Razorpay Test Mode gateway instead of the mock — see [docs/API.md § Configuring real Razorpay Test Mode](docs/API.md#configuring-real-razorpay-test-mode) |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | unset | Razorpay **Test Mode** credentials only (`rzp_test_...`) — never live keys |
| `RAZORPAY_WEBHOOK_SECRET` | unset | Verifies inbound `POST /api/webhooks/razorpay` deliveries; also required for the demo console's signed **test** confirmation button (works even with the mock gateway) |
| `DEMO_SEED_ENABLED` | `false` | Seeds the deterministic demo dataset at startup; also gates `POST /api/demo/recovery/reset` (requires this **and** `RAZORPAY_ENABLED=false`) |
| `RATE_LIMIT_ENABLED` / `RATE_LIMIT_REQUESTS_PER_WINDOW` / `RATE_LIMIT_WINDOW_SECONDS` | `true` / `20` / `60` | Per-client request throttling |
| `BATCH_MAX_TRANSACTION_COUNT` / `BATCH_MAX_AGGREGATE_AMOUNT` | `20` / `100000` | Hard server-side ceilings on batch execution |

### Resetting demo data

Once `DEMO_SEED_ENABLED=true`, `POST /api/demo/recovery/reset` (`MERCHANT_ADMIN` only) wipes and re-seeds the entire demo dataset back to its original deterministic state in one call — useful for re-running the failure-path scenarios from a clean slate after batch executions, webhook confirmations, or other testing. It refuses (`409`) if `RAZORPAY_ENABLED=true`, and never touches login accounts. There's a **"Reset demo data"** button for this on the `/demo/recovery` page (next to "Refresh dashboard"), or call it directly:

```bash
curl -X POST http://localhost:8080/api/demo/recovery/reset \
  -H "Authorization: Bearer $TOKEN"
```

See [docs/API.md § `POST /api/demo/recovery/reset`](docs/API.md#post-apidemorecoveryreset-merchant_admin-only).

## Live links

- Frontend: https://recoverai-bay.vercel.app
- Recovery console: https://recoverai-bay.vercel.app/demo/recovery
- Backend health check: https://recoverai-xrky.onrender.com/api/health
- Repository: https://github.com/poojaaxx/recoverai

## Final takeaway

The interesting part of RecoverAI isn't asking an LLM what to do about a failed payment — that's the easy part. It's closing the whole loop: detecting revenue that's actually worth chasing, getting a useful recommendation from AI, enforcing deterministic rules that decide what's actually allowed to run, executing only within those bounds, and only counting money as recovered once a real payment confirmation says so.
