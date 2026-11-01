import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, ClipboardList, Plus, Upload, Users } from 'lucide-react'
import api, { errorMessage } from '../../services/api'
import { GlassCard, WhiteButton, GlassBadge, BackgroundVideo } from '../../components/ui/glass'
import ImportStudentsDialog from '../../components/tpo/ImportStudentsDialog'

const CohortDetail = () => {
  const { orgId, cohortId } = useParams()
  const [students, setStudents] = useState([])
  const [assignments, setAssignments] = useState([])
  const [cohort, setCohorts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [importOpen, setImportOpen] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    const [studentResponse, assignmentResponse, cohortResponse] = await Promise.all([
      api.get(`/orgs/${orgId}/cohorts/${cohortId}/students`),
      api.get(`/orgs/${orgId}/assignments`),
      api.get(`/orgs/${orgId}/cohorts`),
    ])
    setStudents(studentResponse.data || [])
    setAssignments((assignmentResponse.data || []).filter(
      (item) => String(item.cohortId) === String(cohortId),
    ))
    setCohorts(cohortResponse.data || [])
  }, [orgId, cohortId])

  useEffect(() => {
    let alive = true
    setLoading(true)
    load()
      .catch((err) => { if (alive) setError(errorMessage(err, 'Could not load this cohort.')) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [load])

  const currentCohort = cohort.find((item) => String(item.id) === String(cohortId))

  return (
    <div className="min-h-screen relative" style={{ background: '#000', isolation: 'isolate' }}>
      <BackgroundVideo />
      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <Link to="/tpo" className="inline-flex items-center gap-2 text-sm mb-4" style={{ color: 'rgba(255,255,255,.7)' }}>
          <ArrowLeft className="h-4 w-4" /> Placement dashboard
        </Link>

        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <div>
            <h1 className="text-3xl font-bold" style={{ color: '#fff', fontFamily: '"Fraunces", serif', fontWeight: 500 }}>
              {currentCohort?.name || 'Cohort'}
            </h1>
            <p className="mt-1" style={{ color: 'rgba(255,255,255,.72)' }}>
              {currentCohort?.department || 'General'} · {students.length} students · {assignments.length} assignments
            </p>
          </div>
          <div className="flex flex-wrap gap-3">
            <WhiteButton className="px-4 py-2 text-sm inline-flex items-center gap-2" onClick={() => setImportOpen(true)}>
              <Upload className="h-4 w-4" /> Import students
            </WhiteButton>
            <Link to={`/tpo/assignments/new?orgId=${orgId}&cohortId=${cohortId}`}>
              <span className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm" style={{ background: 'rgba(255,255,255,.07)', border: '1px solid rgba(255,255,255,.16)', color: '#fff' }}>
                <Plus className="h-4 w-4" /> New assignment
              </span>
            </Link>
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
          <div className="grid lg:grid-cols-2 gap-6">
            <GlassCard className="p-5" hover={false}>
              <h2 className="text-lg font-semibold mb-3 inline-flex items-center gap-2" style={{ color: '#fff' }}>
                <Users className="h-4 w-4" /> Students
              </h2>
              {students.length === 0 ? (
                <p className="text-sm py-6 text-center" style={{ color: 'rgba(255,255,255,.66)' }}>
                  No students yet. Import a CSV to onboard the cohort.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr style={{ color: 'rgba(255,255,255,.6)' }}>
                        <th className="text-left font-medium py-2">Name</th>
                        <th className="text-left font-medium py-2">Email</th>
                        <th className="text-left font-medium py-2">Phone</th>
                      </tr>
                    </thead>
                    <tbody>
                      {students.map((student) => (
                        <tr key={student.userId} style={{ borderTop: '1px solid rgba(255,255,255,.07)' }}>
                          <td className="py-2" style={{ color: '#fff' }}>{student.fullName}</td>
                          <td className="py-2" style={{ color: 'rgba(255,255,255,.75)' }}>{student.email}</td>
                          <td className="py-2" style={{ color: 'rgba(255,255,255,.6)' }}>{student.phone || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </GlassCard>

            <GlassCard className="p-5" hover={false}>
              <h2 className="text-lg font-semibold mb-3 inline-flex items-center gap-2" style={{ color: '#fff' }}>
                <ClipboardList className="h-4 w-4" /> Assignments
              </h2>
              {assignments.length === 0 ? (
                <p className="text-sm py-6 text-center" style={{ color: 'rgba(255,255,255,.66)' }}>
                  No assignments yet. Pick a company track and set a due date.
                </p>
              ) : (
                <div className="space-y-3">
                  {assignments.map((assignment) => (
                    <div key={assignment.id} className="p-4 rounded-xl"
                      style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.08)' }}>
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <p className="font-medium" style={{ color: '#fff' }}>{assignment.title}</p>
                          <p className="text-xs mt-0.5" style={{ color: 'rgba(255,255,255,.6)' }}>
                            {assignment.trackName} · {assignment.attemptCount} attempts · {assignment.completedCount} completed
                          </p>
                          {assignment.dueAt && (
                            <p className="text-xs mt-1" style={{ color: 'rgba(255,255,255,.5)' }}>
                              Due {new Date(assignment.dueAt).toLocaleString()}
                            </p>
                          )}
                        </div>
                        <GlassBadge variant={assignment.completedCount > 0 ? 'success' : 'default'}>
                          {assignment.maxAttempts} {assignment.maxAttempts === 1 ? 'attempt' : 'attempts'}
                        </GlassBadge>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </GlassCard>
          </div>
        )}
      </div>

      {importOpen && (
        <ImportStudentsDialog
          orgId={orgId}
          cohortId={cohortId}
          onClose={() => setImportOpen(false)}
          onImported={() => load().catch(() => {})}
        />
      )}
    </div>
  )
}

export default CohortDetail
