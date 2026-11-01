import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import api, { errorMessage } from '../services/api'
import { Send, SkipForward, MessageSquare, Mic, Code, ChevronRight, ThumbsUp, ThumbsDown, Minus, Clock } from 'lucide-react'
import VoiceRecorder from '../components/interview/VoiceRecorder'
import VoicePlayer from '../components/interview/VoicePlayer'
import CodingEnvironment from '../components/interview/CodingEnvironment'
import BodyLanguageAnalyzer from '../components/interview/BodyLanguageAnalyzer'
import ProctoringMonitor from '../components/interview/ProctoringMonitor'
import SpeechEmotionAnalyzer from '../components/interview/SpeechEmotionAnalyzer'
import InterviewerOrb from '../components/interview/InterviewerOrb'
import ErrorBoundary from '../components/common/ErrorBoundary'
import ConsentDialog from '../components/privacy/ConsentDialog'
import AnswerInsights from '../components/interview/AnswerInsights'
import LiveAnswerHints from '../components/interview/LiveAnswerHints'
import EvaluationProgress from '../components/interview/EvaluationProgress'
import { analyzeAnswer } from '../utils/answerInsights'
import { motion } from 'framer-motion'
import { GlassCard, WhiteButton, GlassBadge, GlassInput, BackgroundVideo } from '../components/ui/glass'
import { useAuth } from '../context/AuthContext'
import { clearDraft, loadDraft, saveDraft } from '../utils/drafts'

const Interview = () => {
  const { id } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const [interview, setInterview] = useState(null)
  const [currentQuestion, setCurrentQuestion] = useState(null)
  const [error, setError] = useState(null)
  const [answer, setAnswer] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [inputMode, setInputMode] = useState('text') // text, voice, coding
  const [showAvatar, setShowAvatar] = useState(true)
  const [showBodyLanguage, setShowBodyLanguage] = useState(true)
  const [showProctoring, setShowProctoring] = useState(true)
  const [avatarEmotion, setAvatarEmotion] = useState('neutral')
  const [avatarSpeaking, setAvatarSpeaking] = useState(false)
  const [audioStream, setAudioStream] = useState(null)
  const [bodyAnalysis, setBodyAnalysis] = useState(null)
  const [proctoringAccepted, setProctoringAccepted] = useState(false)
  const [consent, setConsent] = useState({ consentRecording: false, consentProctoring: false })
  const [consentOpen, setConsentOpen] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [answerSeconds, setAnswerSeconds] = useState(null)
  const [elapsed, setElapsed] = useState(0)

  const audioStreamRef = useRef(null)
  const recordingStartedRef = useRef(null)
  const draftTimerRef = useRef(null)

  useEffect(() => {
    const timer = setInterval(() => setElapsed((seconds) => seconds + 1), 1000)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    if (!currentQuestion?.id) return
    setAnswer(loadDraft(user, id, currentQuestion.id))
  }, [currentQuestion?.id, user, id])

  useEffect(() => {
    if (!currentQuestion?.id) return
    clearTimeout(draftTimerRef.current)
    draftTimerRef.current = setTimeout(() => {
      saveDraft(user, id, currentQuestion.id, answer)
    }, 400)
    return () => clearTimeout(draftTimerRef.current)
  }, [answer, currentQuestion?.id, user, id])

  useEffect(() => {
    fetchInterview()
    return () => {
      if (audioStreamRef.current) {
        audioStreamRef.current.getTracks().forEach(track => track.stop())
      }
    }
  }, [id])

  useEffect(() => {
    api.get('/me/consent')
      .then(({ data }) => setConsent({
        consentRecording: Boolean(data.consentRecording),
        consentProctoring: Boolean(data.consentProctoring),
        dataRetentionDays: data.dataRetentionDays,
      }))
      .catch(() => {})
  }, [])

  const fetchInterview = async () => {
    setError(null)
    try {
      const response = await api.get(`/interviews/${id}`)
      setInterview(response.data)

      if (response.data.status === 'COMPLETED') {
        navigate(`/report/${id}`, { replace: true })
        return
      }
      if (response.data.status === 'SCHEDULED') {
        await api.post(`/interviews/${id}/begin`)
      }

      const questionResponse = await api.get(`/interviews/${id}/next-question`)
      setCurrentQuestion(questionResponse.data)
    } catch (err) {
      setError(errorMessage(err, 'Could not load this interview. Check your connection and retry.'))
    } finally {
      setLoading(false)
    }
  }

  const handleTranscript = (transcript, isFinal) => {
    if (isFinal) {
      setAnswer(prev => prev + ' ' + transcript)
    }
  }

  const handleRecordingChange = async (recording) => {
    if (recording) {
      recordingStartedRef.current = Date.now()
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        audioStreamRef.current = stream
        setAudioStream(stream)
      } catch {
        setError('Microphone access was denied. You can type your answer instead.')
      }
    } else if (audioStreamRef.current) {
      if (recordingStartedRef.current) {
        const seconds = Math.max(1, Math.round((Date.now() - recordingStartedRef.current) / 1000))
        setAnswerSeconds((prev) => (prev || 0) + seconds)
        recordingStartedRef.current = null
      }
      audioStreamRef.current.getTracks().forEach((track) => track.stop())
      audioStreamRef.current = null
      setAudioStream(null)
    }
  }

  const handleEmotionChange = (data) => {
    if (data?.emotion) {
      setAvatarEmotion(data.emotion)
    }
  }

  const handleCodingSubmit = (result) => {
    setAnswer(JSON.stringify(result))
  }

  const handleSubmitAnswer = async () => {
    if (!answer.trim() || submitting) return

    const insights = analyzeAnswer({
      text: answer,
      seconds: inputMode === 'voice' ? answerSeconds : null,
      behavioral: currentQuestion?.questionType === 'BEHAVIORAL',
    })

    setError(null)
    setSubmitting(true)
    try {
      const response = await api.post(`/interviews/${id}/answer`, {
        sessionId: currentQuestion.id,
        answer: answer,
      })

      clearTimeout(draftTimerRef.current)
      clearDraft(user, id, currentQuestion.id)

      const evalData = response.data?.evaluation
      if (evalData) {
        setFeedback({
          score: evalData.score,
          grade: evalData.grade,
          feedback: evalData.feedback,
          strengths: evalData.strengths,
          weaknesses: evalData.weaknesses,
          sampleResponse: evalData.sampleResponse,
          communicationScore: evalData.communicationScore,
          technicalDepth: evalData.technicalDepth,
          insights,
        })
      } else {
        setFeedback({
          score: null,
          grade: null,
          feedback: 'Answer saved, but the AI evaluator is unavailable. You can continue; the report will say so instead of inventing a score.',
          insights,
        })
      }
    } catch (err) {
      setError(errorMessage(err, 'Your answer was not saved. Please retry.'))
    } finally {
      setSubmitting(false)
    }
  }

  const handleContinue = async () => {
    if (submitting) return
    setError(null)
    setSubmitting(true)
    try {
      if (interview.currentQuestionIndex + 1 >= interview.totalQuestions) {
        await api.post(`/interviews/${id}/end`)
        navigate(`/report/${id}`)
        return
      }
      const res = await api.get(`/interviews/${id}/next-question`)
      setCurrentQuestion(res.data)
      setInterview({ ...interview, currentQuestionIndex: interview.currentQuestionIndex + 1 })
      setFeedback(null)
      setAnswer('')
      setAnswerSeconds(null)
    } catch (err) {
      setError(errorMessage(err, 'Could not move to the next question. Please retry.'))
    } finally {
      setSubmitting(false)
    }
  }

  const handleEndInterview = async () => {
    setError(null)
    try {
      await api.post(`/interviews/${id}/end`)
      navigate(`/report/${id}`)
    } catch (err) {
      setError(errorMessage(err, 'Could not end the interview. Please retry.'))
    }
  }

  if (loading) {
    return (
      <div className="min-h-screen relative" style={{ background: "#000", isolation: "isolate" }}>
        <BackgroundVideo />
        <div className="relative z-10 flex items-center justify-center h-64">
          <div className="animate-spin rounded-full h-8 w-8" style={{ border: '2px solid rgba(255,255,255,.1)', borderTopColor: '#fff' }} />
        </div>
      </div>
    )
  }

  if (error && (!interview || !currentQuestion)) {
    return (
      <div className="min-h-screen relative" style={{ background: "#000", isolation: "isolate" }}>
        <BackgroundVideo />
        <div className="relative z-10 max-w-md mx-auto px-4 py-24 text-center">
          <div role="alert" className="p-6 rounded-2xl" style={{ background: 'rgba(255,255,255,.04)', border: '1px solid rgba(255,255,255,.1)' }}>
            <p className="font-semibold mb-2" style={{ color: '#fff' }}>Interview could not be loaded</p>
            <p className="text-sm mb-5" style={{ color: 'rgba(255,255,255,.6)' }}>{error}</p>
            <div className="flex justify-center gap-3">
              <WhiteButton onClick={() => { setLoading(true); fetchInterview() }} className="px-4 py-2 text-sm">Retry</WhiteButton>
              <button onClick={() => navigate('/')} className="px-4 py-2 text-sm rounded-xl" style={{ color: 'rgba(255,255,255,.6)' }}>Back to dashboard</button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (!interview || !currentQuestion) {
    return (
      <div className="text-center py-12">
        <p style={{ color: 'oklch(100% 0 0 / 0.4)' }}>Interview not found or completed.</p>
      </div>
    )
  }

  const isCodingQuestion = currentQuestion.questionType === 'CODING'

  return (
    <div className="min-h-screen relative" style={{ background: "#000", isolation: "isolate" }}>
      <BackgroundVideo />
      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      {!proctoringAccepted && (
        <div role="region" aria-label="Proctoring notice" className="mb-6 p-5 rounded-2xl max-w-3xl"
          style={{ background: 'rgba(255,255,255,.04)', border: '1px solid rgba(255,255,255,.12)' }}>
          <h2 className="font-semibold mb-2" style={{ color: '#fff' }}>Interview integrity notice</h2>
          <p className="text-sm mb-2" style={{ color: 'rgba(255,255,255,.65)' }}>
            If you enable proctoring, your browser checks — without uploading any video — whether one face is
            present and looking at the screen, and it logs tab switches and clipboard copy/paste. Three repeated
            alerts of the same kind end the interview.
          </p>
          <p className="text-sm mb-4" style={{ color: 'rgba(255,255,255,.74)' }}>
            Proctoring is optional. The interview and its score work the same without it.
          </p>
          <div className="flex flex-wrap gap-3">
            <WhiteButton
              onClick={() => {
                setProctoringAccepted(true)
                setShowProctoring(true)
                if (!consent.consentProctoring) setConsentOpen(true)
              }}
              className="px-4 py-2 text-sm"
            >
              Enable proctoring
            </WhiteButton>
            <button onClick={() => { setProctoringAccepted(true); setShowProctoring(false) }}
              className="px-4 py-2 text-sm rounded-xl" style={{ color: 'rgba(255,255,255,.6)' }}>
              Continue without it
            </button>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Main Interview Area */}
        <div className="xl:col-span-2">
          {error && (
            <div role="alert" className="mb-6 p-4 rounded-xl text-sm" style={{ background: 'rgba(239,68,68,.12)', border: '1px solid rgba(239,68,68,.35)', color: '#fecaca' }}>
              {error}
            </div>
          )}
          {/* Header */}
          <GlassCard className="mb-6 p-6">
            <div className="flex justify-between items-center mb-4">
              <h1 className="text-xl font-bold" style={{ color: '#fff' }}>{interview.title}</h1>
              <div className="flex items-center gap-3">
                <span className="inline-flex items-center gap-1 text-xs" style={{ color: 'rgba(255,255,255,.72)' }}>
                  <Clock className="h-3.5 w-3.5" />
                  {Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, '0')}
                </span>
                <GlassBadge variant="primary">Question {interview.currentQuestionIndex + 1} of {interview.totalQuestions}</GlassBadge>
              </div>
            </div>
            <div className="w-full h-2 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,.05)' }}>
              <div className="h-2 rounded-full transition-all duration-500"
                style={{ width: `${((interview.currentQuestionIndex + 1) / interview.totalQuestions) * 100}%`, background: '#f59e0b' }} />
            </div>
          </GlassCard>

          {/* Digital Avatar */}
          {showAvatar && (
            <div className="mb-6">
              <InterviewerOrb
                state={avatarSpeaking ? 'speaking' : 'idle'}
                name="AI Interviewer"
              />
            </div>
          )}

          {/* Question Card */}
          <GlassCard className="mb-6 p-6">
            <div className="flex items-start space-x-3">
              <div className="flex-shrink-0">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ background: '#f59e0b' }}>
                  <MessageSquare className="h-5 w-5 text-white" />
                </div>
              </div>
              <div className="flex-1">
                <p className="text-sm font-semibold mb-1" style={{ color: '#fbbf24' }}>AI Interviewer</p>
                <p style={{ color: '#fff' }}>{currentQuestion.questionText}</p>
                <div className="mt-2 flex space-x-2">
                  <GlassBadge variant="primary">{currentQuestion.questionType}</GlassBadge>
                  <GlassBadge variant="warning">{currentQuestion.difficulty}</GlassBadge>
                </div>
                <div className="mt-4"><VoicePlayer text={currentQuestion.questionText} autoPlay={false} onSpeakingChange={setAvatarSpeaking} /></div>
              </div>
            </div>
          </GlassCard>

          {/* Follow-up Question */}
          {currentQuestion.aiFollowUp && (
            <GlassCard className="mb-6 p-5" style={{ background: 'linear-gradient(145deg, rgba(251,191,36,.08), rgba(245,158,11,.05))', borderColor: 'rgba(251,191,36,.2)' }}>
              <p className="text-sm font-medium mb-1" style={{ color: '#fbbf24' }}>Follow-up Question</p>
              <p style={{ color: 'rgba(255,220,150,.9)' }}>{currentQuestion.aiFollowUp}</p>
            </GlassCard>
          )}

          {/* Input Mode Selector */}
          <GlassCard className="mb-6 p-4">
            <div className="flex space-x-2">
              {[
                { mode: 'text', icon: MessageSquare, label: 'Text' },
                { mode: 'voice', icon: Mic, label: 'Voice' },
                ...(isCodingQuestion ? [{ mode: 'coding', icon: Code, label: 'Code' }] : []),
              ].map(({ mode, icon: Icon, label }) => (
                <button key={mode} onClick={() => {
                  if (mode === 'voice' && !consent.consentRecording) {
                    setConsentOpen(true)
                    return
                  }
                  setInputMode(mode)
                }} className="flex items-center px-4 py-2 rounded-xl text-sm font-medium transition-all"
                  style={{ background: inputMode === mode ? '#f59e0b' : 'rgba(255,255,255,.06)', color: inputMode === mode ? '#151515' : 'rgba(255,255,255,.68)', border: `1px solid ${inputMode === mode ? '#f59e0b' : 'rgba(255,255,255,.08)'}` }}>
                  <Icon className="w-4 h-4 mr-2" />{label}
                </button>
              ))}
            </div>
            {!consent.consentRecording && (
              <p className="text-xs mt-3" style={{ color: 'rgba(255,255,255,.55)' }}>
                Recording off — voice stays disabled until you grant consent.
              </p>
            )}
          </GlassCard>

          {/* Answer Input */}
          {inputMode === 'coding' && isCodingQuestion ? (
            <GlassCard className="mb-6 p-0 overflow-hidden h-[720px]">
              <CodingEnvironment problem={{ id: currentQuestion.id, title: 'Coding challenge', description: currentQuestion.questionText }} onSubmit={handleCodingSubmit} />
            </GlassCard>
          ) : (
            <GlassCard className="mb-6 p-6">
              {inputMode === 'voice' ? (
                <VoiceRecorder onTranscript={handleTranscript} onRecordingChange={handleRecordingChange} disabled={submitting} />
              ) : (
                <>
                  <label className="block text-sm font-medium mb-2" style={{ color: 'rgba(255,255,255,.6)' }}>Your Answer</label>
                  <GlassInput as="textarea" rows={6} value={answer} onChange={(e) => setAnswer(e.target.value)} placeholder="Type your answer here..." style={{ resize: 'none' }}
                    onKeyDown={(e) => {
                      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                        e.preventDefault()
                        handleSubmitAnswer()
                      }
                    }} />
                </>
              )}

              {!feedback && (
                <>
                  <LiveAnswerHints text={answer} question={currentQuestion.questionText} behavioral={currentQuestion.questionType === 'BEHAVIORAL'} />
                  <p className="text-[11px] mt-1" style={{ color: 'rgba(255,255,255,.64)' }}>
                    Draft saves on this device · Ctrl/⌘ + Enter to submit
                  </p>
                </>
              )}

              {feedback ? (
                <GlassCard className="mt-4 p-5" style={{ background: 'linear-gradient(145deg, rgba(24,22,20,.6), rgba(10,15,18,.7))' }}>
                  <h3 className="text-sm font-bold mb-3" style={{ color: '#fff' }}>Feedback</h3>
                  <div className="space-y-3 mb-4">
                    <p className="text-sm leading-relaxed" style={{ color: 'rgba(255,255,255,.7)' }}>{feedback.feedback}</p>
                    {feedback.strengths && <div><p className="text-xs font-semibold mb-1" style={{ color: '#4ade80' }}>Strengths</p><p className="text-sm" style={{ color: 'rgba(255,255,255,.65)' }}>{feedback.strengths}</p></div>}
                    {feedback.weaknesses && <div><p className="text-xs font-semibold mb-1" style={{ color: '#fbbf24' }}>Areas to Improve</p><p className="text-sm" style={{ color: 'rgba(255,255,255,.65)' }}>{feedback.weaknesses}</p></div>}
                  </div>
                  {feedback.sampleResponse && (
                    <details className="mb-4"><summary className="text-xs font-semibold cursor-pointer hover:opacity-80" style={{ color: '#fbbf24' }}>Sample Response</summary>
                      <p className="mt-2 text-sm leading-relaxed p-3 rounded-xl" style={{ color: 'rgba(255,255,255,.6)', background: 'rgba(255,255,255,.04)', border: '1px solid rgba(255,255,255,.06)' }}>{feedback.sampleResponse}</p>
                    </details>
                  )}
                  {feedback.insights && (
                    <AnswerInsights
                      insights={feedback.insights}
                      behavioral={currentQuestion.questionType === 'BEHAVIORAL'}
                    />
                  )}
                  <WhiteButton onClick={handleContinue} disabled={submitting} className="w-full py-2.5 text-sm mt-2">
                    {submitting ? 'Generating...' : interview.currentQuestionIndex + 1 >= interview.totalQuestions ? 'View Report' : 'Continue to Next Question'}
                  </WhiteButton>
                </GlassCard>
              ) : (
                <>
                <div className="mt-4 flex justify-between">
                  <button onClick={handleEndInterview} className="px-4 py-2 text-sm font-medium rounded-xl" style={{ color: 'rgba(255,255,255,.65)' }}>
                    <SkipForward className="h-4 w-4 mr-2 inline" />End Interview
                  </button>
                  <WhiteButton onClick={handleSubmitAnswer} disabled={submitting || !answer.trim()} className="px-5 py-2.5 text-sm">
                    <Send className="h-4 w-4 mr-2 inline" />{submitting ? 'Submitting...' : 'Submit Answer'}
                  </WhiteButton>
                </div>
                {submitting && <EvaluationProgress active />}
                </>
              )}
            </GlassCard>
          )}
        </div>

        {/* Sidebar */}
        <div className="space-y-6">
          <ErrorBoundary label="analysis-panels" compact>
            <SpeechEmotionAnalyzer audioStream={audioStream} onEmotionChange={handleEmotionChange} enabled={inputMode === 'voice'} />
            {showBodyLanguage && consent.consentProctoring && <BodyLanguageAnalyzer enabled={true} onAnalysisUpdate={setBodyAnalysis} />}
            {showProctoring && proctoringAccepted && consent.consentProctoring && <ProctoringMonitor onViolation={async (v) => { if (v.type === 'interview_end') { try { await api.post(`/interviews/${id}/end`) } catch (e) { console.error(e) } alert('Interview terminated.'); navigate('/') } }} enabled={showProctoring} />}
          </ErrorBoundary>
          <GlassCard className="p-4">
            <h3 className="font-medium mb-3" style={{ color: '#fff' }}>Analysis Panels</h3>
            <div className="space-y-2">
              {[
                { label: 'Digital Avatar', checked: showAvatar, onChange: setShowAvatar },
                { label: 'Body Language', checked: showBodyLanguage, onChange: setShowBodyLanguage },
                { label: 'Proctoring', checked: showProctoring, onChange: setShowProctoring },
              ].map(({ label, checked, onChange }) => (
                <label key={label} className="flex items-center space-x-2 cursor-pointer">
                  <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} className="rounded" style={{ accentColor: '#f59e0b' }} />
                  <span className="text-sm" style={{ color: 'rgba(255,255,255,.6)' }}>{label}</span>
                </label>
              ))}
            </div>
          </GlassCard>
        </div>
      </div>
      </div>

      <ConsentDialog
        open={consentOpen}
        initial={consent}
        onAccepted={(data) => {
          setConsent(data)
          setConsentOpen(false)
        }}
        onDeclined={() => {
          setConsentOpen(false)
          setShowProctoring(false)
          if (inputMode === 'voice') setInputMode('text')
        }}
      />
    </div>
  )
}

export default Interview
