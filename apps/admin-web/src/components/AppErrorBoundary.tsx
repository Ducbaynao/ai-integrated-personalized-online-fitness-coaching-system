import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { StatePanel } from './StatePanel.tsx'

interface AppErrorBoundaryProps {
  children: ReactNode
}

interface AppErrorBoundaryState {
  hasError: boolean
}

export class AppErrorBoundary extends Component<
  AppErrorBoundaryProps,
  AppErrorBoundaryState
> {
  state: AppErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Admin application render failure', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <StatePanel
          title="Đã xảy ra lỗi"
          description="Vui lòng tải lại trang để tiếp tục."
          action={<button onClick={() => window.location.reload()}>Tải lại trang</button>}
          tone="danger"
        />
      )
    }

    return this.props.children
  }
}
