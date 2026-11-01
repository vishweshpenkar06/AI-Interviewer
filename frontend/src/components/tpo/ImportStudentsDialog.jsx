import { useState } from 'react'
import api, { errorMessage } from '../../services/api'
import { GlassCard, GlassInput, WhiteButton, GlassButton, GlassBadge } from '../ui/glass'

const SAMPLE = `fullName,email,phone,department,graduationYear
Asha Rao,asha@example.com,9876543210,CSE,2027
Ravi Kumar,ravi@example.com,,IT,2027`

const ImportStudentsDialog = ({ orgId, cohortId, onClose, onImported }) => {
  const [csv, setCsv] = useState('')
  const [fileName, setFileName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const onFile = (event) => {
    const file = event.target.files?.[0]
    if (!file) return
    setFileName(file.name)
    const reader = new FileReader()
    reader.onload = () => setCsv(String(reader.result || ''))
    reader.readAsText(file)
  }

  const submit = async () => {
    if (!csv.trim()) {
      setError('Paste the CSV or choose a file first.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const form = new FormData()
      form.append('file', new Blob([csv], { type: 'text/csv' }), fileName || 'students.csv')
      const { data } = await api.post(
        `/orgs/${orgId}/cohorts/${cohortId}/students/import`,
        form,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      )
      setResult(data)
      onImported?.(data)
    } catch (err) {
      setError(errorMessage(err, 'The import failed. Check the CSV and retry.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4" style={{ background: 'rgba(0,0,0,.7)' }}>
      <GlassCard className="w-full max-w-2xl p-6" hover={false}>
        <div className="flex items-start justify-between gap-4 mb-4">
          <div>
            <h2 className="text-xl font-semibold" style={{ color: '#fff' }}>Import students</h2>
            <p className="text-sm mt-1" style={{ color: 'rgba(255,255,255,.66)' }}>
              Columns: fullName,email,phone,department,graduationYear. Duplicate emails are skipped, not duplicated.
            </p>
          </div>
          <GlassBadge>CSV</GlassBadge>
        </div>

        <label className="block text-xs mb-1" style={{ color: 'rgba(255,255,255,.66)' }}>Choose a file</label>
        <input
          type="file"
          accept=".csv,text/csv"
          onChange={onFile}
          className="mb-3 text-sm"
          style={{ color: 'rgba(255,255,255,.8)' }}
        />
        <GlassInput
          as="textarea"
          rows={8}
          value={csv}
          onChange={(event) => setCsv(event.target.value)}
          placeholder={SAMPLE}
          style={{ fontFamily: 'ui-monospace, monospace', fontSize: 12 }}
        />

        {error ? <p className="mt-3 text-sm" style={{ color: '#f87171' }}>{error}</p> : null}

        {result ? (
          <div className="mt-4 p-3 rounded-xl text-sm" style={{ background: 'rgba(34,197,94,.08)', border: '1px solid rgba(34,197,94,.2)' }}>
            <p style={{ color: '#4ade80' }}>{result.created} created · {result.skipped} skipped</p>
            {result.errors?.length ? (
              <ul className="mt-2 list-disc pl-5" style={{ color: 'rgba(255,255,255,.75)' }}>
                {result.errors.map((item) => <li key={item}>{item}</li>)}
              </ul>
            ) : null}
          </div>
        ) : null}

        <div className="mt-5 flex justify-end gap-3">
          <GlassButton type="button" className="px-4 py-2 text-sm" onClick={onClose}>
            {result ? 'Done' : 'Cancel'}
          </GlassButton>
          <WhiteButton type="button" className="px-4 py-2 text-sm" onClick={submit} disabled={busy}>
            {busy ? 'Importing…' : 'Import'}
          </WhiteButton>
        </div>
      </GlassCard>
    </div>
  )
}

export default ImportStudentsDialog
