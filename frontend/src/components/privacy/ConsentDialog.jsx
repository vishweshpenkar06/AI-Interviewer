import { useState } from 'react'
import api, { errorMessage } from '../../services/api'
import { GlassCard, WhiteButton, GlassButton } from '../ui/glass'

/**
 * Recording and proctoring are opt-in. Accepting saves the flags the backend
 * checks before any audio is processed.
 */
const ConsentDialog = ({ open, onAccepted, onDeclined, initial = {} }) => {
  const [recording, setRecording] = useState(Boolean(initial.consentRecording))
  const [proctoring, setProctoring] = useState(Boolean(initial.consentProctoring))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  if (!open) return null

  const accept = async () => {
    setBusy(true)
    setError(null)
    try {
      const { data } = await api.post('/me/consent', {
        consentRecording: recording,
        consentProctoring: proctoring,
      })
      onAccepted?.(data)
    } catch (err) {
      setError(errorMessage(err, 'Could not save your consent. Please retry.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4" style={{ background: 'rgba(0,0,0,.72)' }}>
      <GlassCard className="w-full max-w-lg p-6" hover={false}>
        <h2 className="text-xl font-semibold" style={{ color: '#fff' }}>Before you start</h2>
        <p className="text-sm mt-2" style={{ color: 'rgba(255,255,255,.7)' }}>
          This interview can use your microphone and browser camera. Nothing is processed until you agree,
          and you can withdraw consent at any time from your profile.
        </p>

        <label className="mt-5 flex items-start gap-3 text-sm" style={{ color: 'rgba(255,255,255,.86)' }}>
          <input type="checkbox" className="mt-1" checked={recording} onChange={(event) => setRecording(event.target.checked)} />
          <span>
            <strong>Voice answers.</strong> Process my microphone audio for transcription. Recordings are not
            kept in the database and retention is limited.
          </span>
        </label>

        <label className="mt-4 flex items-start gap-3 text-sm" style={{ color: 'rgba(255,255,255,.86)' }}>
          <input type="checkbox" className="mt-1" checked={proctoring} onChange={(event) => setProctoring(event.target.checked)} />
          <span>
            <strong>In-browser proctoring.</strong> Face/presence checks and tab-switch signals run locally.
            Raw camera frames are not uploaded.
          </span>
        </label>

        {error ? <p className="mt-3 text-sm" style={{ color: '#f87171' }}>{error}</p> : null}

        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <GlassButton type="button" className="px-4 py-2 text-sm" onClick={onDeclined} disabled={busy}>
            Continue without these
          </GlassButton>
          <WhiteButton type="button" className="px-4 py-2 text-sm" onClick={accept} disabled={busy}>
            {busy ? 'Saving…' : 'Save and continue'}
          </WhiteButton>
        </div>
      </GlassCard>
    </div>
  )
}

export default ConsentDialog
