import { lazy, Suspense } from 'react'
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import ProtectedRoute from './components/common/ProtectedRoute'
import ErrorBoundary from './components/common/ErrorBoundary'
import TpoRoute from './components/tpo/TpoRoute'
import { UIChrome, UIPage } from './v2/UISwitch'

const Landing = lazy(() => import('./pages/Landing'))
const Login = lazy(() => import('./pages/Login'))
const Register = lazy(() => import('./pages/Register'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const NewInterview = lazy(() => import('./pages/NewInterview'))
const Interview = lazy(() => import('./pages/Interview'))
const Report = lazy(() => import('./pages/Report'))
const JobDescriptions = lazy(() => import('./pages/JobDescriptions'))
const Resumes = lazy(() => import('./pages/Resumes'))
const ResumeBuilder = lazy(() => import('./pages/ResumeBuilder'))
const Practice = lazy(() => import('./pages/Practice'))
const Profile = lazy(() => import('./pages/Profile'))
const AuthCallback = lazy(() => import('./pages/AuthCallback'))
const LegalPages = import('./pages/Legal')
const Privacy = lazy(() => LegalPages.then((m) => ({ default: m.Privacy })))
const Terms = lazy(() => LegalPages.then((m) => ({ default: m.Terms })))
const Cookies = lazy(() => LegalPages.then((m) => ({ default: m.Cookies })))

const StudioHome = lazy(() => import('./v2/pages/Home'))
const StudioBrief = lazy(() => import('./v2/pages/Brief'))
const StudioLive = lazy(() => import('./v2/pages/StudioLive'))
const StudioReport = lazy(() => import('./v2/pages/ReportStudio'))
const StudioLibrary = lazy(() => import('./v2/pages/Library'))
const StudioSettings = lazy(() => import('./v2/pages/Settings'))
const StudioAuth = lazy(() => import('./v2/pages/Auth'))
const TpoDashboard = lazy(() => import('./pages/tpo/Dashboard'))
const TpoCohortDetail = lazy(() => import('./pages/tpo/CohortDetail'))
const TpoAssignmentBuilder = lazy(() => import('./pages/tpo/AssignmentBuilder'))
const Pricing = lazy(() => import('./pages/Pricing'))

function AppRoutes() {
  const { user, loading } = useAuth()

  if (loading) {
    return (
      <div className="min-h-screen bg-black flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-white/20 border-t-white"></div>
      </div>
    )
  }

  return (
    <Suspense fallback={<div className="min-h-screen bg-black flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-2 border-white/20 border-t-white" /></div>}>
    <Routes>
      <Route path="/login" element={user ? <Navigate to="/" replace /> : (
        <UIPage classic={<Login />} studio={<StudioAuth mode="login" />} />
      )} />
      <Route path="/register" element={user ? <Navigate to="/" replace /> : (
        <UIPage classic={<Register />} studio={<StudioAuth mode="register" />} />
      )} />
      <Route path="/privacy" element={<Privacy />} />
      <Route path="/terms" element={<Terms />} />
      <Route path="/cookies" element={<Cookies />} />
      <Route path="/pricing" element={<Pricing />} />
      <Route path="/auth/callback" element={<AuthCallback />} />
      <Route
        path="/"
        element={
          user ? (
            <ProtectedRoute>
              <UIChrome />
            </ProtectedRoute>
          ) : (
            <Landing />
          )
        }
      >
        {user && (
          <>
            <Route index element={<UIPage classic={<Dashboard />} studio={<StudioHome />} />} />
            <Route path="interview/new" element={<UIPage classic={<NewInterview />} studio={<StudioBrief />} />} />
            <Route path="interview/:id" element={<UIPage classic={<Interview />} studio={<StudioLive />} />} />
            <Route path="report/:id" element={<UIPage classic={<Report />} studio={<StudioReport />} />} />
            <Route path="job-descriptions" element={<UIPage classic={<JobDescriptions />} studio={<StudioLibrary initialTab="jobs" />} />} />
            <Route path="resumes" element={<UIPage classic={<Resumes />} studio={<StudioLibrary initialTab="resumes" />} />} />
            <Route path="resume-builder" element={<ResumeBuilder />} />
            <Route path="practice" element={<UIPage classic={<Practice />} studio={<Practice />} />} />
            <Route path="profile" element={<UIPage classic={<Profile />} studio={<StudioSettings />} />} />
            <Route path="tpo" element={<TpoDashboard />} />
            <Route
              path="tpo/cohorts/:orgId/:cohortId"
              element={<TpoRoute><TpoCohortDetail /></TpoRoute>}
            />
            <Route
              path="tpo/assignments/new"
              element={<TpoRoute><TpoAssignmentBuilder /></TpoRoute>}
            />
          </>
        )}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </Suspense>
  )
}

const HIDE_GLOBAL_VIDEO = ['/privacy', '/terms', '/cookies', '/auth/callback']

function GlobalBackdrop() {
  const { pathname } = useLocation()
  if (HIDE_GLOBAL_VIDEO.includes(pathname)) return null
  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden' }}>
      <video
        style={{
          position: 'absolute', inset: 0,
          width: '100%', height: '100%',
          objectFit: 'cover', objectPosition: 'center',
          filter: 'blur(3px) brightness(.92) saturate(1.15)',
          transform: 'scale(1.05)',
          pointerEvents: 'none', userSelect: 'none',
        }}
        autoPlay muted loop playsInline disablePictureInPicture aria-hidden="true"
      >
        <source src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260808_064556_051587f1-74a1-4336-8c05-4dde3594ed05.mp4" type="video/mp4" />
      </video>
      {/* Scrim: keeps the footage visible while giving white text a readable base. */}
      <div
        style={{
          position: 'absolute', inset: 0,
          background: 'linear-gradient(180deg, rgba(4,5,8,.42), rgba(4,5,8,.55) 55%, rgba(4,5,8,.68))',
          pointerEvents: 'none',
        }}
        aria-hidden="true"
      />
    </div>
  )
}

function App() {
  return (
    <AuthProvider>
      <Router>
        <GlobalBackdrop />
        <ErrorBoundary label="app">
          <AppRoutes />
        </ErrorBoundary>
      </Router>
    </AuthProvider>
  )
}

export default App
