import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Building2, Download, Plus, Users, ClipboardList, ArrowRight } from 'lucide-react'
import api, { errorMessage } from '../../services/api'
import { useAuth } from '../../context/AuthContext'
import { GlassCard, WhiteButton, GlassInput, GlassBadge, BackgroundVideo } from '../../components/ui/glass'
import AnalyticsCards from '../../components/tpo/AnalyticsCards'

const TpoDashboard = () => {
  const { user, selectOrganization } = useAuth()
  const [organizations, setOrganizations] = useState([])
  const [orgId, setOrgId] = useState(null)
  const [cohorts, setCohorts] = useState([])
  const [analytics, setAnalytics] = useState(null)
  const [usage, setUsage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [orgName, setOrgName] = useState('')
  const [newCohort, setNewCohort] = useState({ name: '', department: '', graduationYear: 2027 })
  const [busy, setBusy] = useState(false)

  const loadOrganizations = useCallback(async () => {
    const { data } = await api.get('/me/organizations')
    setOrganizations(data)
    const preferred = data.find((org) => org.id === user?.organizationId)
      || data.find((org) => ['TPO', 'ADMIN'].includes(org.role))
      || data[0]
    if (preferred) {
      setOrgId(preferred.id)
      selectOrganization(preferred.id)
    }
    return data
  }, [selectOrganization, user?.organizationId])

  const loadOrgData = useCallback(async (id) => {
    if (!id) return
    const [cohortResponse, analyticsResponse, usageResponse] = await Promise.all([
      api.get(`/orgs/${id}/cohorts`).catch(() => ({ data: [] })),
      api.get(`/orgs/${id}/analytics`).catch(() => ({ data: null })),
      api.get(`/orgs/${id}/usage`).catch(() => ({ data: null })),
    ])
    setCohorts(cohortResponse.data || [])
    setAnalytics(analyticsResponse.data)
    setUsage(usageResponse.data)
  }, [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    loadOrganizations()
      .catch((err) => { if (alive) setError(errorMessage(err, 'Could not load your organisations.')) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [loadOrganizations])

  useEffect(() => {
    if (!orgId) return
    loadOrgData(orgId).catch((err) => setError(errorMessage(err, 'Could not load this organisation.')))
  }, [orgId, loadOrgData])

  const createOrganization = async (event) => {
    event.preventDefault()
    if (!orgName.trim()) return
    setBusy(true)
    setError(null)
    try {
      const { data } = await api.post('/orgs', { name: orgName.trim() })
      setOrgName('')
      selectOrganization(data.id)
      await loadOrganizations()
      setOrgId(data.id)
      await loadOrgData(data.id)
    } catch (err) {
      setError(errorMessage(err, 'Could not create the organisation.'))
    } finally {
      setBusy(false)
    }
  }

  const createCohort = async (event) => {
    event.preventDefault()
    if (!newCohort.name.trim() || !orgId) return
    setBusy(true)
    setError(null)
    try {
      await api.post(`/orgs/${orgId}/cohorts`, {
        name: newCohort.name.trim(),
        department: newCohort.department || null,
        graduationYear: Number(newCohort.graduationYear) || null,
      })
      setNewCohort({ name: '', department: '', graduationYear: 2027 })
      await loadOrgData(orgId)
    } catch (err) {
      setError(errorMessage(err, 'Could not create the cohort.'))
    } finally {
      setBusy(false)
    }
  }

  const exportCsv = async () => {
    if (!orgId) return
    try {
      const response = await api.get(`/orgs/${orgId}/reports/export`, { responseType: 'blob' })
      const url = URL.createObjectURL(new Blob([response.data], { type: 'text/csv' }))
      const link = document.createElement('a')
      link.href = url
      link.download = 'placementos-reports.csv'
      link.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(errorMessage(err, 'Could not export reports.'))
    }
  }

  const currentOrg = organizations.find((org) => org.id === orgId)

  return (
    <div className="min-h-screen relative" style={{ background: '#000', isolation: 'isolate' }}>
      <BackgroundVideo />
      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <div>
            <h1 className="text-3xl font-bold" style={{ color: '#fff', fontFamily: '"Fraunces", serif', fontWeight: 500 }}>
              PlacementOS
            </h1>
            <p className="mt-1" style={{ color: 'rgba(255,255,255,.76)' }}>
              Cohorts, assignments and readiness analytics for your college.
            </p>
          </div>
          <div className="flex items-center gap-3">
            {currentOrg && <GlassBadge variant="primary">{currentOrg.plan} plan</GlassBadge>}
            <Link to="/" className="text-sm" style={{ color: 'rgba(255,255,255,.7)' }}>Back to interviews</Link>
          </div>
        </div>

        {error && (
          <div role="alert" className="mb-5 p-3 rounded-xl text-sm" style={{ background: 'rgba(239,68,68,.1)', border: '1px solid rgba(239,68,68,.25)', color: '#fca5a5' }}>
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin rounded-full h-8 w-8" style={{ border: '2px solid rgba(255,255,255,.1)', borderTopColor: '#fff' }} />
          </div>
        ) : (
          <>
            {organizations.length === 0 ? (
              <GlassCard className="p-8 max-w-xl" hover={false}>
                <Building2 className="h-8 w-8 mb-3" style={{ color: '#fbbf24' }} />
                <h2 className="text-xl font-semibold" style={{ color: '#fff' }}>Create your first organisation</h2>
                <p className="text-sm mt-1 mb-5" style={{ color: 'rgba(255,255,255,.7)' }}>
                  You become the administrator of this tenant. Invite students by importing a cohort CSV next.
                </p>
                <form onSubmit={createOrganization} className="flex flex-col sm:flex-row gap-3">
                  <GlassInput
                    value={orgName}
                    onChange={(event) => setOrgName(event.target.value)}
                    placeholder="Saraswati College of Engineering"
                    aria-label="Organisation name"
                  />
                  <WhiteButton type="submit" className="px-4 py-2 text-sm" disabled={busy}>
                    {busy ? 'Creating…' : 'Create organisation'}
                  </WhiteButton>
                </form>
              </GlassCard>
            ) : (
              <div className="space-y-6">
                <div className="flex flex-wrap items-center gap-3">
                  <label className="text-sm" style={{ color: 'rgba(255,255,255,.7)' }}>Organisation</label>
                  <select
                    value={orgId || ''}
                    onChange={(event) => {
                      const next = Number(event.target.value)
                      setOrgId(next)
                      selectOrganization(next)
                    }}
                    className="rounded-xl px-3 py-2 text-sm"
                    style={{ background: '#15181c', color: '#fff', border: '1px solid rgba(255,255,255,.16)' }}
                  >
                    {organizations.map((org) => (
                      <option key={org.id} value={org.id}>{org.name} · {org.role}</option>
                    ))}
                  </select>
                  <button onClick={exportCsv} className="inline-flex items-center gap-1.5 text-sm" style={{ color: '#a5b4fc' }}>
                    <Download className="h-4 w-4" /> Export reports CSV
                  </button>
                  <Link to="/tpo/assignments/new" className="text-sm" style={{ color: '#a5b4fc' }}>
                    Assignment builder
                  </Link>
                </div>

                <AnalyticsCards analytics={analytics} />

                <div className="grid lg:grid-cols-3 gap-6">
                  <GlassCard className="p-5 lg:col-span-2" hover={false}>
                    <div className="flex items-center justify-between gap-3 mb-4">
                      <h2 className="text-lg font-semibold inline-flex items-center gap-2" style={{ color: '#fff' }}>
                        <Users className="h-4 w-4" /> Cohorts
                      </h2>
                      <span className="text-xs" style={{ color: 'rgba(255,255,255,.6)' }}>
                        {usage ? `${usage.students} students · ${usage.interviews} interviews · ${analytics?.attemptsPerStudent ?? 0} attempts/student` : ''}
                      </span>
                    </div>
                    {cohorts.length === 0 ? (
                      <p className="text-sm py-6" style={{ color: 'rgba(255,255,255,.66)' }}>
                        No cohorts yet. Create one on the right, then import students.
                      </p>
                    ) : (
                      <div className="space-y-3">
                        {cohorts.map((cohort) => (
                          <Link key={cohort.id} to={`/tpo/cohorts/${orgId}/${cohort.id}`} className="block">
                            <div className="p-4 rounded-xl flex items-center justify-between gap-4"
                              style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.08)' }}>
                              <div>
                                <p className="font-medium" style={{ color: '#fff' }}>{cohort.name}</p>
                                <p className="text-xs mt-0.5" style={{ color: 'rgba(255,255,255,.6)' }}>
                                  {cohort.department || 'General'} · {cohort.studentCount} students · {cohort.assignmentCount} assignments
                                </p>
                              </div>
                              <ArrowRight className="h-4 w-4" style={{ color: 'rgba(255,255,255,.5)' }} />
                            </div>
                          </Link>
                        ))}
                      </div>
                    )}
                  </GlassCard>

                  <GlassCard className="p-5" hover={false}>
                    <h2 className="text-lg font-semibold mb-3 inline-flex items-center gap-2" style={{ color: '#fff' }}>
                      <ClipboardList className="h-4 w-4" /> New cohort
                    </h2>
                    <form onSubmit={createCohort} className="space-y-3">
                      <GlassInput
                        value={newCohort.name}
                        onChange={(event) => setNewCohort({ ...newCohort, name: event.target.value })}
                        placeholder="CSE 2027"
                        aria-label="Cohort name"
                      />
                      <GlassInput
                        value={newCohort.department}
                        onChange={(event) => setNewCohort({ ...newCohort, department: event.target.value })}
                        placeholder="CSE"
                        aria-label="Department"
                      />
                      <GlassInput
                        type="number"
                        min={2000}
                        max={2100}
                        value={newCohort.graduationYear}
                        onChange={(event) => setNewCohort({ ...newCohort, graduationYear: event.target.value })}
                        aria-label="Graduation year"
                      />
                      <WhiteButton type="submit" className="w-full px-4 py-2 text-sm inline-flex items-center justify-center gap-2" disabled={busy}>
                        <Plus className="h-4 w-4" /> Create cohort
                      </WhiteButton>
                    </form>
                  </GlassCard>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export default TpoDashboard
