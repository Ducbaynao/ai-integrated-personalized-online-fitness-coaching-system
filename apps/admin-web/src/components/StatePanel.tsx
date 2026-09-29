import type { ReactNode } from 'react'

interface StatePanelProps {
  title: string
  description?: string
  action?: ReactNode
  tone?: 'neutral' | 'danger'
  headingLevel?: 1 | 2
}

export function StatePanel({
  title,
  description,
  action,
  tone = 'neutral',
  headingLevel = 1,
}: StatePanelProps) {
  const Heading = headingLevel === 1 ? 'h1' : 'h2'
  return (
    <section className={`state-panel state-panel--${tone}`} aria-live="polite">
      <Heading>{title}</Heading>
      {description ? <p>{description}</p> : null}
      {action ? <div className="state-panel__action">{action}</div> : null}
    </section>
  )
}
