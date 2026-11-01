import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import api, { AUTH_REQUEST_TIMEOUT_MS } from '../services/api'
import { perfEnd, perfStart } from '../utils/perf'

const AuthContext = createContext(null)

/**
 * localStorage is synchronous, so the session can be restored during the first
 * render instead of flashing the full-screen loading spinner for a tick.
 * Corrupt entries are cleared rather than crashing the app.
 */
const readStoredUser = () => {
  const token = localStorage.getItem('token')
  const rawUser = localStorage.getItem('user')
  if (!token || !rawUser) {
    // A half-cleared session must not keep sending a stale Authorization
    // header to the login endpoint.
    if (token || rawUser) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
    return null
  }

  try {
    const user = JSON.parse(rawUser)
    if (!user || typeof user !== 'object') throw new Error('Invalid stored user')
    return user
  } catch {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    return null
  }
}

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(readStoredUser)
  // There is no network request on boot, so the session is known immediately.
  const [loading] = useState(false)

  const login = useCallback(async (email, password) => {
    const startedAt = perfStart()
    try {
      const response = await api.post(
        '/auth/login',
        { email, password },
        { timeout: AUTH_REQUEST_TIMEOUT_MS },
      )
      const { token, ...userData } = response.data
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(userData))
      if (userData.organizationId) {
        localStorage.setItem('organizationId', String(userData.organizationId))
      }
      setUser(userData)
      perfEnd('auth.login', startedAt, { ok: true })
      return userData
    } catch (error) {
      perfEnd('auth.login', startedAt, { ok: false, status: error?.response?.status ?? 'network' })
      throw error
    }
  }, [])

  const register = useCallback(async (data) => {
    const startedAt = perfStart()
    try {
      const response = await api.post(
        '/auth/register',
        data,
        { timeout: AUTH_REQUEST_TIMEOUT_MS },
      )
      const { token, ...userData } = response.data
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(userData))
      if (userData.organizationId) {
        localStorage.setItem('organizationId', String(userData.organizationId))
      }
      setUser(userData)
      perfEnd('auth.register', startedAt, { ok: true })
      return userData
    } catch (error) {
      perfEnd('auth.register', startedAt, { ok: false, status: error?.response?.status ?? 'network' })
      throw error
    }
  }, [])

  /** Used by the Google callback: the JWT arrives in the URL fragment. */
  const loginWithToken = useCallback(async (token) => {
    localStorage.setItem('token', token)
    const startedAt = perfStart()
    try {
      const { data } = await api.get('/users/profile', { timeout: AUTH_REQUEST_TIMEOUT_MS })
      const userData = { userId: data.id, email: data.email, fullName: data.fullName, role: data.role }
      try {
        const orgs = await api.get('/me/organizations', { timeout: AUTH_REQUEST_TIMEOUT_MS })
        if (orgs.data?.length) {
          userData.organizationId = orgs.data[0].id
          localStorage.setItem('organizationId', String(orgs.data[0].id))
        }
      } catch {
        // Organisations are optional for the classic single-user flow.
      }
      localStorage.setItem('user', JSON.stringify(userData))
      setUser(userData)
      perfEnd('auth.google-callback', startedAt, { ok: true })
      return userData
    } catch (err) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      setUser(null)
      perfEnd('auth.google-callback', startedAt, { ok: false, status: err?.response?.status ?? 'network' })
      throw err
    }
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    localStorage.removeItem('organizationId')
    setUser(null)
  }, [])

  /** Switches the tenant used by X-Organization-Id for subsequent requests. */
  const selectOrganization = useCallback((organizationId) => {
    if (organizationId) {
      localStorage.setItem('organizationId', String(organizationId))
    } else {
      localStorage.removeItem('organizationId')
    }
    setUser((previous) => {
      if (!previous) return previous
      const next = { ...previous, organizationId: organizationId || null }
      localStorage.setItem('user', JSON.stringify(next))
      return next
    })
  }, [])

  // Stable context value: consumers only re-render when the session changes,
  // not whenever the provider happens to render.
  const value = useMemo(
    () => ({ user, loading, login, register, loginWithToken, logout, selectOrganization }),
    [user, loading, login, register, loginWithToken, logout, selectOrganization],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export const useAuth = () => {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
