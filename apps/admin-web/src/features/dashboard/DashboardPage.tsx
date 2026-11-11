import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { userAdminApi } from '../../services/userAdminApi.ts'
import type { AdminDashboardSummary } from '../../types/userAdmin.ts'

export function DashboardPage() {
  const [summary, setSummary] = useState<AdminDashboardSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchSummary = useCallback(() => {
    setLoading(true)
    setError(null)
    userAdminApi
      .getDashboardSummary()
      .then((data) => {
        setSummary(data)
      })
      .catch((err) => {
        setError(err instanceof Error ? err.message : 'Không thể tải số liệu tổng quan')
      })
      .finally(() => {
        setLoading(false)
      })
  }, [])

  useEffect(() => {
    let active = true
    userAdminApi
      .getDashboardSummary()
      .then((data) => {
        if (active) setSummary(data)
      })
      .catch((err) => {
        if (active) setError(err instanceof Error ? err.message : 'Không thể tải số liệu tổng quan')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const hasActionRequired =
    summary != null &&
    (summary.pendingTrainerApplications > 0 || summary.suspendedUsers > 0)

  return (
    <section className="page-stack">
      <div className="page-header">
        <div>
          <p className="eyebrow">A05/M8 &amp; A06/M3</p>
          <h1>Trang quản trị</h1>
        </div>
      </div>

      {loading ? (
        <StatePanel title="Đang tải dữ liệu tổng quan…" />
      ) : error || !summary ? (
        <StatePanel
          title="Không thể tải bảng điều khiển"
          description={error ?? 'Đã xảy ra lỗi không xác định'}
          action={
            <button className="button-secondary" onClick={() => fetchSummary()}>
              Thử lại
            </button>
          }
          tone="danger"
        />
      ) : (
        <>
          {hasActionRequired && (
            <div className="surface" style={{ borderLeft: '4px solid var(--color-warning, #f59e0b)' }}>
              <h2>Công việc cần xử lý (Action Required)</h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1rem', marginTop: '1rem' }}>
                {summary.pendingTrainerApplications > 0 && (
                  <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', background: '#fffbeb' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <h3 style={{ margin: 0, fontSize: '1rem' }}>Hồ sơ HLV chờ duyệt</h3>
                      <span style={{ fontSize: '1.25rem', fontWeight: 'bold', color: '#b45309' }}>
                        {summary.pendingTrainerApplications}
                      </span>
                    </div>
                    <p style={{ margin: '0.5rem 0', fontSize: '0.875rem', color: '#4b5563' }}>
                      Có hồ sơ huấn luyện viên mới cần xác minh và phê duyệt chứng chỉ.
                    </p>
                    <Link to="/trainer-applications" className="button-secondary" style={{ display: 'inline-block', marginTop: '0.5rem' }}>
                      Đi đến xét duyệt hồ sơ &rarr;
                    </Link>
                  </div>
                )}

                {summary.suspendedUsers > 0 && (
                  <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', background: '#fef2f2' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <h3 style={{ margin: 0, fontSize: '1rem' }}>Tài khoản đang tạm đình chỉ</h3>
                      <span style={{ fontSize: '1.25rem', fontWeight: 'bold', color: '#dc2626' }}>
                        {summary.suspendedUsers}
                      </span>
                    </div>
                    <p style={{ margin: '0.5rem 0', fontSize: '0.875rem', color: '#4b5563' }}>
                      Tài khoản đang bị tạm đình chỉ hoạt động cần xem xét hoặc khôi phục.
                    </p>
                    <Link to="/users?status=SUSPENDED" className="button-secondary" style={{ display: 'inline-block', marginTop: '0.5rem' }}>
                      Xem danh sách tạm đình chỉ &rarr;
                    </Link>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="surface">
            <h2>Tổng quan quản trị tài khoản</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '1rem', marginTop: '1rem' }}>
              <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', textAlign: 'center' }}>
                <span style={{ fontSize: '0.875rem', color: '#6b7280' }}>Tổng tài khoản</span>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.75rem', fontWeight: 'bold' }}>{summary.totalUsers}</p>
              </div>
              <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', textAlign: 'center' }}>
                <span style={{ fontSize: '0.875rem', color: '#16a34a' }}>Đang hoạt động</span>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.75rem', fontWeight: 'bold', color: '#16a34a' }}>{summary.activeUsers}</p>
              </div>
              <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', textAlign: 'center' }}>
                <span style={{ fontSize: '0.875rem', color: '#d97706' }}>Chờ xác thực</span>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.75rem', fontWeight: 'bold', color: '#d97706' }}>{summary.pendingVerificationUsers}</p>
              </div>
              <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', textAlign: 'center' }}>
                <span style={{ fontSize: '0.875rem', color: '#dc2626' }}>Tạm đình chỉ</span>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.75rem', fontWeight: 'bold', color: '#dc2626' }}>{summary.suspendedUsers}</p>
              </div>
              <div style={{ padding: '1rem', border: '1px solid #e5e7eb', borderRadius: '8px', textAlign: 'center' }}>
                <span style={{ fontSize: '0.875rem', color: '#6b7280' }}>Đã vô hiệu hoá</span>
                <p style={{ margin: '0.5rem 0 0 0', fontSize: '1.75rem', fontWeight: 'bold', color: '#6b7280' }}>{summary.disabledUsers}</p>
              </div>
            </div>
          </div>

          <div className="surface">
            <h2>Lối tắt quản trị</h2>
            <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginTop: '1rem' }}>
              <Link to="/users" className="button-secondary">
                Tra cứu &amp; quản trị người dùng
              </Link>
              <Link to="/trainer-applications" className="button-secondary">
                Xét duyệt hồ sơ HLV
              </Link>
            </div>
          </div>
        </>
      )}
    </section>
  )
}
