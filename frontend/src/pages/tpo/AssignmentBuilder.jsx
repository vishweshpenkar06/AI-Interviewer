import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowLeft, ClipboardList } from 'lucide-react'
import api, { errorMessage } from '../../services/api'
import { useAuth } from '../../context/AuthContext'
import { GlassCard, GlassInput, WhiteButton, BackgroundVideo } from '../../components/ui/glass'

const AssignmentBuilder = () => {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [params] = useSearchParams()
  const initialOrg = params.get('orgId')
  const initialCohort = params.get('cohortId')

  const [organizations, setOrganizations] = useState([])
  const [orgId, setOrgId] = useState(initialOrg ? Number(initialOrg) : null)
  const [cohorts, setCohorts] = useState([])
  const [tracks, setTracks] = useState([])
  const [form, setForm] = useState({
    cohortId: initialCohort || '',
    trackId: '',
    title: '',
    dueAt: '',
    maxAttempts: 1,
  })
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    api.get('/me/organizations')
      .then(({ data }) => {
        if (!alive) return
        setOrganizations(data)
        if (!orgId) {
          const preferred = data.find((org) => org.id === user?.organizationId)
            || data.find((org) => ['TPO', 'ADMIN'].includes(org.role))
            || data[0]
          if (preferred) setOrgId(preferred.id)
        }
      })
      .catch((err) => { if (alive) setError(errorMessage(err, 'Could not load your organisations.')) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [orgId, user?.organizationId])

  useEffect(() => {
    if (!orgId) return
    let alive = true
    Promise.all([
      api.get(`/orgs/${orgId}/cohorts`),
      api.get(`/orgs/${orgId}/tracks`),
    ]).then(([cohortResponse, trackResponse]) => {
      if (!alive) return
      setCohorts(cohortResponse.data || [])
      setTracks(trackResponse.data || [])
      setForm((current) => ({
        ...current,
        cohortId: current.cohortId || cohortResponse.data?.[0]?.id || '',
        trackId: current.trackId || trackResponse.data?.[0]?.id || '',
      }))
    }).catch((err) => {
      if (alive) setError(errorMessage(err, 'Could not load cohorts or tracks.'))
    })
    return () => { alive = false }
  }, [orgId])

  const selectedTrack = useMemo(
    () => tracks.find((track) => String(track.id) === String(form.trackId)),
    [tracks, form.trackId],
  )

  const submit = async (event) => {
    event.preventDefault()
    if (!orgId || !form.title.trim() || !form.cohortId) return
    setBusy(true)
    setError(null)
    try {
      await api.post(`/orgs/${orgId}/assignments`, {
        cohortId: Number(form.cohortId),
        trackId: form.trackId ? Number(form.trackId) : null,
        title: form.title.trim(),
        dueAt: form.dueAt ? `${form.dueAt}:00` : null,
        maxAttempts: Math.max(1, Number(form.maxAttempts) || 1),
      })
      navigate(`/tpo/cohorts/${orgId}/${form.cohortId}`)
    } catch (err) {
      setError(errorMessage(err, 'Could not create the assignment.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen relative" style={{ background: '#000', isolation: 'isolate' }}>
      <BackgroundVideo />
      <div className="relative z-10 max-w-3xl mx-auto px-4 sm:px-6 py-6">
        <Link to="/tpo" className="inline-flex items-center gap-2 text-sm mb-4" style={{ color: 'rgba(255,255,255,.7)' }}>
          <ArrowLeft className="h-4 w-4" /> Placement dashboard
        </Link>

        <div className="mb-6">
          <h1 className="text-3xl font-bold" style={{ color: '#fff', fontFamily: '"Fraunces", serif', fontWeight: 500 }}>
            Build an assignment
          </h1>
          <p className="mt-1" style={{ color: 'rgba(255,255,255,.72)' }}>
            Track sections become question types; track weights drive the report score.
          </p>
        </div>

        {error && (
          <div role="alert" className="mb-4 p-3 rounded-xl text-sm" style={{ background: 'rgba(239,68,68,.1)', border: '1px solid rgba(239,68,68,.25)', color: '#fca5a5' }}>
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center h-48">
            <div className="animate-spin rounded-full h-8 w-8" style={{ border: '2px solid rgba(255,255,255,.1)', borderTopColor: '#fff' }} />
          </div>
        ) : (
          <GlassCard className="p-6" hover={false}>
            <form onSubmit={submit} className="space-y-4">
              <div className="grid sm:grid-cols-2 gap-4">
                <label className="block">
                  <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Organisation</span>
                  <select
                    value={orgId || ''}
                    onChange={(event) => { setOrgId(Number(event.target.value)); setForm({ ...form, cohortId: '', trackId: '' }) }}
                    className="w-full rounded-xl px-3 py-2.5 text-sm"
                    style={{ background: '#15181c', color: '#fff', border: '1px solid rgba(255,255,255,.16)' }}
                  >
                    {organizations.map((org) => (
                      <option key={org.id} value={org.id}>{org.name}</option>
                    ))}
                  </select>
                </label>

                <label className="block">
                  <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Cohort</span>
                  <select
                    value={form.cohortId}
                    onChange={(event) => setForm({ ...form, cohortId: event.target.value })}
                    className="w-full rounded-xl px-3 py-2.5 text-sm"
                    style={{ background: '#15181c', color: '#fff', border: '1px solid rgba(255,255,255,.16)' }}
                  >
                    <option value="">Select a cohort</option>
                    {cohorts.map((cohort) => (
                      <option key={cohort.id} value={cohort.id}>{cohort.name} ({cohort.studentCount} students)</option>
                    ))}
                  </select>
                </label>
              </div>

              <label className="block">
                <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Company track</span>
                <select
                  value={form.trackId}
                  onChange={(event) => setForm({ ...form, trackId: event.target.value })}
                  className="w-full rounded-xl px-3 py-2.5 text-sm"
                  style={{ background: '#15181c', color: '#fff', border: '1px solid rgba(255,255,255,.16)' }}
                >
                  <option value="">General interview</option>
                  {tracks.map((track) => (
                    <option key={track.id} value={track.id}>{track.name}</option>
                  ))}
                </select>
              </label>

              {selectedTrack && (
                <div className="p-3 rounded-xl text-xs space-y-1"
                  style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.08)', color: 'rgba(255,255,255,.7)' }}>
                  <p><strong style={{ color: '#fff' }}>{selectedTrack.name}</strong> · {selectedTrack.config?.questionCount} questions · {selectedTrack.config?.durationMinutes} min · {selectedTrack.config?.difficulty}</p>
                  <p>Sections: {(selectedTrack.config?.sections || []).join(' → ')}</p>
                  <p>Weights: {Object.entries(selectedTrack.config?.rubricWeights || {}).map(([key, value]) => `${key.replace('_', ' ')} ${Math.round(value * 100)}%`).join(' · ')}</p>
                </div>
              )}

              <label className="block">
                <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Assignment title</span>
                <GlassInput
                  value={form.title}
                  onChange={(event) => setForm({ ...form, title: event.target.value })}
                  placeholder="TCS NQT mock — CSE 2027"
                />
              </label>

              <div className="grid sm:grid-cols-2 gap-4">
                <label className="block">
                  <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Due date (optional)</span>
                  <GlassInput
                    type="datetime-local"
                    value={form.dueAt}
                    onChange={(event) => setForm({ ...form, dueAt: event.target.value })}
                  />
                </label>
                <label className="block">
                  <span className="block text-sm mb-1.5" style={{ color: 'rgba(255,255,255,.8)' }}>Max attempts</span>
                  <GlassInput
                    type="number"
                    min={1}
                    max={20}
                    value={form.maxAttempts}
                    onChange={(event) => setForm({ ...form, maxAttempts: event.target.value })}
                  />
                </label>
              </div>

              <div className="flex justify-end gap-3 pt-2">
                <Link to="/tpo" className="px-4 py-2 text-sm" style={{ color: 'rgba(255,255,255,.7)' }}>Cancel</Link>
                <WhiteButton type="submit" disabled={busy} className="px-4 py-2 text-sm inline-flex items-center gap-2">
                  <ClipboardList className="h-4 w-4" /> {busy ? 'Creating…' : 'Create assignment'}
                </WhiteButton>
              </div>
            </form>
          </GlassCard>
        )}
      </div>
    </div>
  )
}

export default AssignmentBuilder
