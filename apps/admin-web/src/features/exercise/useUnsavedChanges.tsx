import { useCallback, useEffect } from 'react'
import { useBlocker, useNavigate } from 'react-router-dom'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'

export function useUnsavedChanges(dirty: boolean) {
  const navigate = useNavigate()
  const blocker = useBlocker(({ currentLocation, nextLocation }) => (
    dirty &&
    `${currentLocation.pathname}${currentLocation.search}${currentLocation.hash}` !==
      `${nextLocation.pathname}${nextLocation.search}${nextLocation.hash}`
  ))

  const requestNavigation = useCallback((path: string) => {
    navigate(path)
  }, [navigate])

  useEffect(() => {
    if (!dirty) return
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
    }
  }, [dirty])

  const dialog = (
    <ConfirmationDialog
      open={blocker.state === 'blocked'}
      title="Bạn có thay đổi chưa lưu"
      description="Nếu rời trang, các thay đổi chưa lưu sẽ bị mất."
      confirmLabel="Rời trang"
      onCancel={() => blocker.reset?.()}
      onConfirm={() => blocker.proceed?.()}
    />
  )

  return { requestNavigation, unsavedChangesDialog: dialog }
}
