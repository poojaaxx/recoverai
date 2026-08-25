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

None of this is exotic — it's mostly "don't trust anything twice, and fail toward the safe option."

## Measuring recovered revenue

This is the part I was most careful about, because it's easy to get wrong (or fake).

Execution success is not the same as payment success. A recovery action succeeding just means a provider call went through — e.g. a payment link got created. It does not mean the customer paid. The transaction only gets marked `RECOVERED`, and `amountRecovered` only becomes non-zero, after a Razorpay webhook arrives, its signature is verified, and the confirmed amount/currency are checked against what was actually authorized.

I'm being upfront about where this currently stands: **the deployed environment doesn't have real Razorpay Test Mode credentials configured**, so it runs on the mock/simulation gateway. That means confirmed recovered revenue in this environment is genuinely ₹0.00 — always has been. The confirmation pipeline itself (signature verification, correlation, idempotency, the whole thing) is implemented and covered by tests that drive a real signed webhook through the real endpoint. It's just never had a real payment to confirm. I'd rather say that plainly than dress up a number that isn't real.

## Demo

Live app: **https://recoverai-bay.vercel.app/demo/recovery**

You'll be asked to log in first — use `merchant.admin` / `RecoverAI-Judge-Admin-2026` for full access (including executing a recovery), or `operator` / `RecoverAI-Judge-Operator-2026` for a read-only view.

The `/demo/recovery` console walks through 5 named scenarios — an easy recovery that gets ALLOWed, a high-value transaction that gets ESCALATEd instead of auto-retried, a transaction that already hit its STOP limit, one that's already recovered, and one already escalated. Each button (Analyze Risk, Get AI Recommendation, Evaluate Policy, Execute Recovery) calls the real backend, nothing is precomputed. There's also a `/transactions` dashboard for browsing and filtering any transaction in the database, not just the 5 curated ones.

## Tech stack

**Backend:** Java 17, Spring Boot, Spring Data JPA, PostgreSQL (Neon), Flyway, Maven
**Frontend:** React, TypeScript, Vite
**AI:** deterministic mock provider by default; an Anthropic Claude integration exists and works if you give it an API key
**Payments:** Razorpay adapter behind a `PaymentGateway` interface — mock by default, real Test Mode is an opt-in config flag
**Deployment:** Render (backend), Vercel (frontend), Neon (PostgreSQL)

## Build quality

291 backend tests passing (`mvn test`), frontend builds clean (`npm run build`). That includes a test that runs the actual Flyway migrations against a real, temporary PostgreSQL instance rather than just H2, concurrency tests that hit the execution and webhook endpoints with real parallel threads, and webhook tests that go through real signature verification rather than a fake bypass. It's all deployed and reachable live, not just running locally.

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

That runs the backend against an in-memory H2 database with demo data seeded, no PostgreSQL setup required. See `.env.example` for every config option, including how to point it at real PostgreSQL or turn on the Anthropic/Razorpay integrations.

## Live links

- Frontend: https://recoverai-bay.vercel.app
- Recovery console: https://recoverai-bay.vercel.app/demo/recovery
- Backend health check: https://recoverai-xrky.onrender.com/api/health
- Repository: https://github.com/poojaaxx/recoverai

## Final takeaway

The interesting part of RecoverAI isn't asking an LLM what to do about a failed payment — that's the easy part. It's closing the whole loop: detecting revenue that's actually worth chasing, getting a useful recommendation from AI, enforcing deterministic rules that decide what's actually allowed to run, executing only within those bounds, and only counting money as recovered once a real payment confirmation says so.
