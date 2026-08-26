import { Badge, type BadgeTone } from './Badge'

interface AuditTimelineEntryLike {
  id: string
  eventType: string
  actor: string
  decision: string | null
  reason: string | null
  timestamp: string
}

const EVENT_TYPE_LABELS: Record<string, string> = {
  RISK_DETECTED: 'Risk detected',
  RECOVERY_AI_RECOMMENDATION: 'AI recommendation generated',
  RECOVERY_POLICY_EVALUATED: 'Policy evaluated',
  RECOVERY_EXECUTION_STARTED: 'Execution started',
  RECOVERY_EXECUTION_COMPLETED: 'Execution completed',
  RECOVERY_EXECUTION_FAILED: 'Execution failed',
  RECOVERY_EXECUTION_BLOCKED: 'Execution blocked',
  RECOVERY_EXECUTION_ESCALATED: 'Execution escalated',
  RECOVERY_EXECUTION_STOPPED: 'Execution stopped',
  RECOVERY_EXECUTION_NOT_APPLICABLE: 'Execution not applicable',
  RECOVERY_ATTEMPT_RECORDED: 'Recovery attempt recorded',
  RECOVERY_BATCH_SKIPPED_PORTFOLIO_LIMIT: 'Skipped — batch portfolio limit',
  PAYMENT_WEBHOOK_RECEIVED: 'Payment webhook received',
  PAYMENT_CONFIRMATION_VERIFIED: 'Payment confirmation verified',
  PAYMENT_RECOVERY_CONFIRMED: 'Payment recovery confirmed',
  PAYMENT_CONFIRMATION_REJECTED: 'Payment confirmation rejected',
}

const ACTOR_LABELS: Record<string, string> = {
  POLICY_ENGINE: 'Policy Engine',
  AI_AGENT: 'AI Agent',
  RECOVERY_EXECUTION_SERVICE: 'Execution Service',
  BATCH_RECOVERY_SERVICE: 'Batch Recovery Service',
  PAYMENT_CONFIRMATION_SERVICE: 'Confirmation Service',
  SEED_SCRIPT: 'Seed Script',
}

export function humanizeEventType(eventType: string): string {
  return EVENT_TYPE_LABELS[eventType] ?? eventType.replaceAll('_', ' ')
}

export function humanizeActor(actor: string): string {
  return ACTOR_LABELS[actor] ?? actor
}

/**
 * The four (plus risk) stages of RecoverAI's actual pipeline, in the order
 * money can move through them — Transaction → Risk → AI recommendation →
 * Policy decision → Execution → Payment confirmation. This is purely a
 * *presentation* grouping of the `eventType` strings the backend already
 * writes to `AuditLog`; it introduces no new fact, decision, or event —
 * every entry rendered is a real row this transaction's own history
 * produced, categorized only by which stage its already-known event type
 * belongs to.
 */
type PipelineStage = 'RISK' | 'AI' | 'POLICY' | 'EXECUTION' | 'PAYMENT' | 'OTHER'

const EVENT_STAGE: Record<string, PipelineStage> = {
  RISK_DETECTED: 'RISK',
  RECOVERY_AI_RECOMMENDATION: 'AI',
  RECOVERY_POLICY_EVALUATED: 'POLICY',
  RECOVERY_EXECUTION_STARTED: 'EXECUTION',
  RECOVERY_EXECUTION_COMPLETED: 'EXECUTION',
  RECOVERY_EXECUTION_FAILED: 'EXECUTION',
  RECOVERY_EXECUTION_BLOCKED: 'EXECUTION',
  RECOVERY_EXECUTION_ESCALATED: 'EXECUTION',
  RECOVERY_EXECUTION_STOPPED: 'EXECUTION',
  RECOVERY_EXECUTION_NOT_APPLICABLE: 'EXECUTION',
  RECOVERY_ATTEMPT_RECORDED: 'EXECUTION',
  RECOVERY_BATCH_SKIPPED_PORTFOLIO_LIMIT: 'EXECUTION',
  PAYMENT_WEBHOOK_RECEIVED: 'PAYMENT',
  PAYMENT_CONFIRMATION_VERIFIED: 'PAYMENT',
  PAYMENT_RECOVERY_CONFIRMED: 'PAYMENT',
  PAYMENT_CONFIRMATION_REJECTED: 'PAYMENT',
}

export function stageOf(eventType: string): PipelineStage {
  return EVENT_STAGE[eventType] ?? 'OTHER'
}

export const STAGE_LABELS: Partial<Record<PipelineStage, string>> = {
  AI: 'AI recommendation',
  POLICY: 'Policy decision',
  EXECUTION: 'Execution',
  PAYMENT: 'Payment confirmation',
}

export const STAGE_TONE: Record<PipelineStage, BadgeTone> = {
  RISK: 'neutral',
  AI: 'accent',
  POLICY: 'warning',
  EXECUTION: 'neutral',
  PAYMENT: 'success',
  OTHER: 'neutral',
}

const STAGE_DOT_CLASS: Record<PipelineStage, string> = {
  RISK: 'bg-[var(--color-text-secondary)]',
  AI: 'bg-[var(--color-accent)]',
  POLICY: 'bg-[var(--color-warning)]',
  EXECUTION: 'bg-[var(--color-text-secondary)]',
  PAYMENT: 'bg-[var(--color-success)]',
  OTHER: 'bg-[var(--color-text-secondary)]',
}

/**
 * A compact "which stages did this transaction's real history actually
 * reach" strip. A stage lights up only if at least one real audit row of
 * that stage exists for these entries — e.g. a BLOCKed transaction never
 * lights up Execution or Payment, and that gap is exactly the honest
 * story (nothing was skipped in the display, nothing happened for real).
 */
function PipelineProgress({ entries }: { entries: AuditTimelineEntryLike[] }) {
  const reached = new Set(entries.map((e) => stageOf(e.eventType)))
  const stages: PipelineStage[] = ['AI', 'POLICY', 'EXECUTION', 'PAYMENT']
  return (
    <div className="mb-3 flex flex-wrap items-center gap-1.5 text-xs">
      {stages.map((stage, i) => (
        <span key={stage} className="flex items-center gap-1.5">
          {i > 0 && <span className="text-[var(--color-text-secondary)]">→</span>}
          <span
            className={reached.has(stage) ? '' : 'opacity-40 grayscale'}
            title={reached.has(stage) ? `${STAGE_LABELS[stage]} occurred` : `${STAGE_LABELS[stage]} did not occur`}
          >
            <Badge tone={reached.has(stage) ? STAGE_TONE[stage] : 'neutral'}>{STAGE_LABELS[stage]}</Badge>
          </span>
        </span>
      ))}
    </div>
  )
}

/**
 * Shared audit timeline renderer — every field shown comes straight from
 * the backend's AuditLog rows; labels are humanized for readability only,
 * the raw event type stays visible too. Entries are additionally grouped
 * by pipeline stage (see {@link PipelineProgress}) purely for legibility —
 * no reordering, no synthesized events, no policy logic re-implemented.
 */
export function AuditTimeline({ entries }: { entries: AuditTimelineEntryLike[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-[var(--color-text-secondary)]">No audit events yet.</p>
  }
  return (
    <div>
      <PipelineProgress entries={entries} />
      <ol className="space-y-2 border-l border-[var(--color-border)] pl-4">
        {entries.map((entry) => {
          const stage = stageOf(entry.eventType)
          return (
            <li key={entry.id} className="relative text-sm">
              <span className={`absolute -left-[21px] top-1.5 h-2 w-2 rounded-full ${STAGE_DOT_CLASS[stage]}`} />
              <div className="flex flex-wrap items-baseline gap-1.5">
                {STAGE_LABELS[stage] && (
                  <Badge tone={STAGE_TONE[stage]}>{STAGE_LABELS[stage]}</Badge>
                )}
                <span className="font-medium text-[var(--color-text-primary)]">{humanizeEventType(entry.eventType)}</span>
                <span className="font-mono text-[10px] text-[var(--color-accent)]" title="Raw event type">
                  {entry.eventType}
                </span>
              </div>
              <div className="text-[var(--color-text-secondary)]">
                {humanizeActor(entry.actor)}
                {entry.decision ? ` · ${entry.decision}` : ''} · {new Date(entry.timestamp).toLocaleTimeString()}
              </div>
              {entry.reason && <div className="mt-0.5 text-[var(--color-text-secondary)]">{entry.reason}</div>}
            </li>
          )
        })}
      </ol>
    </div>
  )
}
