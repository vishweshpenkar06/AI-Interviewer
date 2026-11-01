import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import api from '../../services/api'
import { useAuth } from '../../context/AuthContext'

/**
 * Tenant pages are only reachable to TPO/ADMIN members. The backend enforces
 * the same rule on every org route; this guard just avoids a dead page.
 */
const TpoRoute = ({ children }) => {
  const { user } = useAuth()
  const [state, setState] = useState('checking')

  useEffect(() => {
    let alive = true
    api.get('/me/organizations')
      .then(({ data }) => {
        if (!alive) return
        const allowed = Array.isArray(data)
          && data.some((org) => org.role === 'TPO' || org.role === 'ADMIN')
        setState(allowed ? 'allowed' : 'denied')
      })
      .catch(() => { if (alive) setState('denied') })
    return () => { alive = false }
  }, [])

  if (!user) return <Navigate to="/login" replace />
  if (state === 'checking') {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8" style={{ border: '2px solid rgba(255,255,255,.1)', borderTopColor: '#fff' }} />
      </div>
    )
  }
  if (state === 'denied') return <Navigate to="/" replace />
  return children
}

export default TpoRoute
