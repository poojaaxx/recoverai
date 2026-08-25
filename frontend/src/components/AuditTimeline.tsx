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
  PAYMENT_WEBHOOK_RECEIVED: 'Payment webhook received',
  PAYMENT_CONFIRMATION_VERIFIED: 'Payment confirmation verified',
  PAYMENT_RECOVERY_CONFIRMED: 'Payment recovery confirmed',
  PAYMENT_CONFIRMATION_REJECTED: 'Payment confirmation rejected',
}

const ACTOR_LABELS: Record<string, string> = {
  POLICY_ENGINE: 'Policy Engine',
  AI_AGENT: 'AI Agent',
  RECOVERY_EXECUTION_SERVICE: 'Execution Service',
  PAYMENT_CONFIRMATION_SERVICE: 'Confirmation Service',
  SEED_SCRIPT: 'Seed Script',
}

export function humanizeEventType(eventType: string): string {
  return EVENT_TYPE_LABELS[eventType] ?? eventType.replaceAll('_', ' ')
}

export function humanizeActor(actor: string): string {
  return ACTOR_LABELS[actor] ?? actor
}

/** Shared audit timeline renderer — every field shown comes straight from the backend's AuditLog rows; labels are humanized for readability only, the raw event type stays visible too. */
export function AuditTimeline({ entries }: { entries: AuditTimelineEntryLike[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-[var(--color-text-secondary)]">No audit events yet.</p>
  }
  return (
    <ol className="space-y-2 border-l border-[var(--color-border)] pl-4">
      {entries.map((entry) => (
        <li key={entry.id} className="relative text-sm">
          <span className="absolute -left-[21px] top-1.5 h-2 w-2 rounded-full bg-[var(--color-accent)]" />
          <div className="flex flex-wrap items-baseline gap-1.5">
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
      ))}
    </ol>
  )
}
