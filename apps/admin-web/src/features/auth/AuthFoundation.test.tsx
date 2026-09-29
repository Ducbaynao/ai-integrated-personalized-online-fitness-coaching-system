import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.tsx'
import { writeSession } from '../../services/sessionStorage.ts'
import { createCurrentUser, createTokenPair } from '../../test/testData.ts'

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
    </MemoryRouter>,
  )
}

describe('authenticated admin foundation', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('redirects an unauthenticated request to the Vietnamese login screen', async () => {
    renderApp('/exercises')

    expect(await screen.findByRole('heading', { name: 'Đăng nhập' })).toBeInTheDocument()
  })

  it('does not infer catalog permission from the ADMIN role', async () => {
    const user = createCurrentUser({ roles: ['ADMIN'], permissions: [] })
    writeSession(createTokenPair(user))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(user)))

    renderApp('/exercises')

    expect(await screen.findByRole('heading', { name: 'Bạn không có quyền truy cập' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Bài tập' })).not.toBeInTheDocument()
  })

  it('shows catalog navigation only with the effective CATALOG_MANAGE permission', async () => {
    const user = createCurrentUser({ permissions: ['CATALOG_MANAGE'] })
    writeSession(createTokenPair(user))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(user)))

    renderApp('/')

    expect(await screen.findByRole('link', { name: 'Bài tập' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Trang quản trị' })).toBeInTheDocument())
  })
})
