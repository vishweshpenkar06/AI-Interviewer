import axios from 'axios'

// Vercel Services routes /api/* to the Spring backend on the same domain, so an
// unset VITE_API_URL means same-origin. Local development keeps the full URL in
// frontend/.env.local.
const apiRoot = (import.meta.env.VITE_API_URL || '').trim().replace(/\/+$/, '')
export const API_BASE = apiRoot ? `${apiRoot}/api` : '/api'

// Auth calls should fail visibly in seconds, not sit behind the 2 minute
// timeout that long-running AI requests need. 30s still covers a Vercel
// container cold start, and the UI explains the wait after 8s.
export const AUTH_REQUEST_TIMEOUT_MS = 30_000
// Provider discovery is a cheap configuration lookup, but it must survive a
// Vercel container cold start; the result is cached for the rest of the session
// and the lookup never blocks the form.
export const PROVIDERS_REQUEST_TIMEOUT_MS = 30_000

const api = axios.create({
  baseURL: API_BASE,
  timeout: 120000,
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // PlacementOS: the backend validates this against a membership row; it is
  // never trusted on its own.
  const organizationId = localStorage.getItem('organizationId')
  if (organizationId) {
    config.headers['X-Organization-Id'] = organizationId
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status

    // Only 401 means the session is gone. A 403 is a normal authorization
    // denial and must not log the user out.
    if (status === 401 && Boolean(error.config?.headers?.Authorization)) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      localStorage.removeItem('organizationId')
      const returnTo = `${window.location.pathname}${window.location.search}`
      window.location.href = `/login?returnTo=${encodeURIComponent(returnTo)}`
    }
    return Promise.reject(error)
  }
)

/**
 * A message that is safe to show a candidate. Prefers the server's own wording
 * (it distinguishes 429, validation and conflicts), then a readable fallback for
 * timeouts, offline and 5xx so the user is never left with "Request failed".
 */
export const errorMessage = (error, fallback = 'Something went wrong. Please retry.') => {
  const status = error?.response?.status
  const serverMessage = error?.response?.data?.message
  if (status === 429) return serverMessage || 'Too many attempts. Wait a minute and try again.'
  if (serverMessage) return serverMessage
  if (error?.code === 'ECONNABORTED') return 'The server took too long to answer. Please retry.'
  if (status >= 500) return 'The server hit an error. Please retry in a moment.'
  if (!error?.response) return 'Cannot reach the server. Check your connection and retry.'
  return fallback
}

export default api
