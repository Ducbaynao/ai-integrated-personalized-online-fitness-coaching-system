import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmationDialog } from './ConfirmationDialog.tsx'

function DialogHarness({ removeTriggerOnConfirm = false }: { removeTriggerOnConfirm?: boolean }) {
  const [open, setOpen] = useState(false)
  const [showTrigger, setShowTrigger] = useState(true)
  return (
    <>
      {showTrigger ? <button onClick={() => setOpen(true)}>Mở xác nhận</button> : null}
      <ConfirmationDialog
        open={open}
        title="Xác nhận thao tác"
        description="Nội dung xác nhận"
        confirmLabel="Xác nhận"
        onCancel={() => setOpen(false)}
        onConfirm={() => {
          if (removeTriggerOnConfirm) setShowTrigger(false)
          setOpen(false)
        }}
      />
    </>
  )
}

describe('ConfirmationDialog', () => {
  it('uses unique accessible IDs for multiple dialogs', () => {
    render(
      <>
        <ConfirmationDialog open title="Dialog one" description="Description one" confirmLabel="Confirm one" onCancel={vi.fn()} onConfirm={vi.fn()} />
        <ConfirmationDialog open title="Dialog two" description="Description two" confirmLabel="Confirm two" onCancel={vi.fn()} onConfirm={vi.fn()} />
      </>,
    )

    const dialogs = screen.getAllByRole('alertdialog')
    expect(dialogs[0].getAttribute('aria-labelledby')).not.toBe(dialogs[1].getAttribute('aria-labelledby'))
    expect(dialogs[0].getAttribute('aria-describedby')).not.toBe(dialogs[1].getAttribute('aria-describedby'))
  })

  it('traps focus in both directions and recovers focus moved outside', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)
    const trigger = screen.getByRole('button', { name: 'Mở xác nhận' })
    await user.click(trigger)

    const cancel = screen.getByRole('button', { name: 'Tiếp tục chỉnh sửa' })
    const confirm = screen.getByRole('button', { name: 'Xác nhận' })
    expect(cancel).toHaveFocus()

    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(confirm).toHaveFocus()
    await user.keyboard('{Tab}')
    expect(cancel).toHaveFocus()

    trigger.focus()
    await user.keyboard('{Tab}')
    expect(cancel).toHaveFocus()
    trigger.focus()
    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(confirm).toHaveFocus()
  })

  it('cancels with Escape and restores focus to the opener', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)
    const trigger = screen.getByRole('button', { name: 'Mở xác nhận' })
    await user.click(trigger)
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('restores focus after confirm when possible and does not throw after opener unmounts', async () => {
    const user = userEvent.setup()
    const first = render(<DialogHarness />)
    const trigger = screen.getByRole('button', { name: 'Mở xác nhận' })
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: 'Xác nhận' }))
    expect(trigger).toHaveFocus()
    first.unmount()

    render(<DialogHarness removeTriggerOnConfirm />)
    await user.click(screen.getByRole('button', { name: 'Mở xác nhận' }))
    await expect(user.click(screen.getByRole('button', { name: 'Xác nhận' }))).resolves.toBeUndefined()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})
