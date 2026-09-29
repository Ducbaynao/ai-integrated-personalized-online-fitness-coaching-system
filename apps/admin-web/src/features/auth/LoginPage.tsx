import { useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../../services/apiClient.ts'
import { useAuth } from './authContextValue.ts'

interface LoginLocationState {
  from?: { pathname?: string; search?: string }
}

export function LoginPage() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  if (auth.status === 'authenticated') {
    return <Navigate to="/" replace />
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await auth.signIn(email, password)
      const state = location.state as LoginLocationState | null
      const destination = state?.from?.pathname
        ? `${state.from.pathname}${state.from.search ?? ''}`
        : '/'
      navigate(destination, { replace: true })
    } catch (error: unknown) {
      setErrorMessage(
        error instanceof ApiError && error.status === 0
          ? 'Không thể kết nối đến hệ thống. Vui lòng thử lại.'
          : 'Email hoặc mật khẩu không chính xác.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <p className="eyebrow">Quản trị hệ thống</p>
        <h1>Đăng nhập</h1>
        <p className="muted">Sử dụng tài khoản được cấp quyền quản trị danh mục.</p>

        <label htmlFor="email">Email</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />

        <label htmlFor="password">Mật khẩu</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />

        {errorMessage ? <p className="form-error" role="alert">{errorMessage}</p> : null}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Đang đăng nhập…' : 'Đăng nhập'}
        </button>
      </form>
    </main>
  )
}
