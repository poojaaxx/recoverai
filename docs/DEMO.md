# Demo script

Status: not yet available as a scripted walkthrough. The full 5-minute
demo flow still depends on the dashboard (Phase 9) and on batch
execution/measured-recovery metrics (a later phase) — but as of Phase 7,
the entire single-transaction pipeline (risk → AI → policy → execution →
audit) is real, wired, and callable end to end.

What *can* already be demonstrated today, via direct API calls against
the 5 named demo transactions (see `RecoveryExecutionDemoScenariosTest`
for the exact, asserted results): risk scoring
(`POST /api/revenue-risk/analyze/{id}`), then running the full execution
pipeline (`POST /api/recovery/{id}/execute`, which internally asks the AI
agent, runs Phase 4's safety policy, and — only on `ALLOW` — calls the
mock `PaymentGateway`) —

- `demo-easy-recovery`: AI recommends `RETRY_PAYMENT` → policy `ALLOW` → **executed** via mock `PaymentGateway` (`simulated=true`, `amountRecovered=0`)
- `demo-high-value`: AI recommends `RETRY_PAYMENT` → policy `ESCALATE` on amount, not risk → not executed
- `demo-retry-escalation`: already `ESCALATED` → policy `ESCALATE` (prevents autonomous retry) → not executed
- `demo-repeated-failure`: AI itself recommends `STOP` → policy `STOP` (already halted) → not executed
- `demo-successful-recovery`: AI does not recommend another recovery action → policy `BLOCK` (already recovered) → not executed

— every case demonstrating both that the policy engine (not the AI)
decides what happens, and that even the one case that *does* execute
never claims money was recovered — "payment link created" is not "money
recovered," see
[README.md § Recovery Execution Pipeline](../README.md#recovery-execution-pipeline-phase-7).

This document will contain the deterministic, repeatable 5-minute demo
path once the dashboard and batch/measured-recovery phases land,
including:

- how to seed the demo dataset (`POST /api/demo/seed`)
- the primary happy-path walkthrough (dashboard → analyze → execute → audit)
- the deliberate failure-recovery scenario (retry limit → safe stop → escalation)
- how to reset state between demo runs (`POST /api/demo/reset`)
