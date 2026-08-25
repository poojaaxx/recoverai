import type { ReactNode } from 'react'

type BadgeTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral'

const toneClasses: Record<BadgeTone, string> = {
  success: 'bg-[color-mix(in_srgb,var(--color-success)_16%,transparent)] text-[var(--color-success)] border-[color-mix(in_srgb,var(--color-success)_40%,transparent)]',
  warning: 'bg-[color-mix(in_srgb,var(--color-warning)_16%,transparent)] text-[var(--color-warning)] border-[color-mix(in_srgb,var(--color-warning)_40%,transparent)]',
  danger: 'bg-[color-mix(in_srgb,var(--color-danger)_16%,transparent)] text-[var(--color-danger)] border-[color-mix(in_srgb,var(--color-danger)_40%,transparent)]',
  accent: 'bg-[color-mix(in_srgb,var(--color-accent)_16%,transparent)] text-[var(--color-accent)] border-[color-mix(in_srgb,var(--color-accent)_40%,transparent)]',
  neutral: 'bg-[var(--color-surface-2)] text-[var(--color-text-secondary)] border-[var(--color-border)]',
}

export function Badge({ tone, children }: { tone: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium tracking-wide whitespace-nowrap ${toneClasses[tone]}`}
    >
      {children}
    </span>
  )
}

export function riskTone(level: string | null | undefined): BadgeTone {
  switch (level) {
    case 'LOW':
      return 'success'
    case 'MEDIUM':
      return 'warning'
    case 'HIGH':
      return 'danger'
    case 'CRITICAL':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function policyTone(decision: string | null | undefined): BadgeTone {
  switch (decision) {
    case 'ALLOW':
      return 'success'
    case 'ESCALATE':
      return 'warning'
    case 'BLOCK':
    case 'STOP':
      return 'danger'
    default:
      return 'neutral'
  }
}

/** Phase 14 — batch execution per-item outcome. */
export function batchOutcomeTone(outcome: string): BadgeTone {
  switch (outcome) {
    case 'EXECUTED':
      return 'success'
    case 'FAILED_PROVIDER_CALL':
    case 'NOT_FOUND':
      return 'danger'
    case 'ESCALATED':
    case 'SKIPPED_PORTFOLIO_LIMIT':
      return 'warning'
    case 'BLOCKED':
    case 'STOPPED':
      return 'danger'
    case 'ALREADY_EXECUTED':
      return 'accent'
    default:
      return 'neutral'
  }
}

export function outcomeTone(outcome: string): BadgeTone {
  switch (outcome) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PENDING CONFIRMATION':
      return 'warning'
    default:
      return 'neutral'
  }
}
