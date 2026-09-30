import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'

export function useUnsavedChanges(dirty: boolean) {
  const navigate = useNavigate()
  const [pendingPath, setPendingPath] = useState<string | null>(null)

  const requestNavigation = useCallback((path: string) => {
    if (dirty) setPendingPath(path)
    else navigate(path)
  }, [dirty, navigate])

  useEffect(() => {
    if (!dirty) return
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    const handleDocumentClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const target = event.target as Element | null
      const anchor = target?.closest<HTMLAnchorElement>('a[href]')
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return
      const url = new URL(anchor.href, window.location.href)
      if (url.origin !== window.location.origin) return
      event.preventDefault()
      event.stopPropagation()
      setPendingPath(`${url.pathname}${url.search}${url.hash}`)
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    document.addEventListener('click', handleDocumentClick, true)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
      document.removeEventListener('click', handleDocumentClick, true)
    }
  }, [dirty])

  const dialog = (
    <ConfirmationDialog
      open={pendingPath !== null}
      title="Bạn có thay đổi chưa lưu"
      description="Nếu rời trang, các thay đổi chưa lưu sẽ bị mất."
      confirmLabel="Rời trang"
      onCancel={() => setPendingPath(null)}
      onConfirm={() => {
        const path = pendingPath
        setPendingPath(null)
        if (path) navigate(path)
      }}
    />
  )

  return { requestNavigation, unsavedChangesDialog: dialog }
}
