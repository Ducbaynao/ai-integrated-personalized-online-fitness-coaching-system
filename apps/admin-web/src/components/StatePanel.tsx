import type { ReactNode } from 'react'

interface StatePanelProps {
  title: string
  description?: string
  action?: ReactNode
  tone?: 'neutral' | 'danger'
}

export function StatePanel({ title, description, action, tone = 'neutral' }: StatePanelProps) {
  return (
    <section className={`state-panel state-panel--${tone}`} aria-live="polite">
      <h1>{title}</h1>
      {description ? <p>{description}</p> : null}
      {action ? <div className="state-panel__action">{action}</div> : null}
    </section>
  )
}
