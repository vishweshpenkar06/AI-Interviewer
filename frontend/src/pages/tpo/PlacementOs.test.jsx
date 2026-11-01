import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TpoDashboard from './Dashboard'
import StudentDashboard from '../Dashboard'
import ConsentDialog from '../../components/privacy/ConsentDialog'
import api from '../../services/api'
import { useAuth } from '../../context/AuthContext'

vi.mock('../../services/api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  errorMessage: (_error, fallback) => fallback,
}))

vi.mock('../../context/AuthContext', () => ({
  useAuth: vi.fn(),
}))

vi.mock('framer-motion', () => ({
  motion: new Proxy({}, {
    get: (_, tag) => React.forwardRef(function MotionElement({ children, ...props }, ref) {
      const domProps = { ...props }
      for (const prop of ['initial', 'animate', 'exit', 'transition', 'whileHover', 'whileTap']) {
        delete domProps[prop]
      }
      return React.createElement(tag, { ...domProps, ref }, children)
    }),
  }),
}))

const renderAt = (element) => render(<MemoryRouter initialEntries={['/']}>{element}</MemoryRouter>)

describe('PlacementOS frontend', () => {
  beforeEach(() => {
    useAuth.mockReturnValue({
      user: { userId: 1, organizationId: 1, fullName: 'TPO' },
      selectOrganization: vi.fn(),
    })
    api.get.mockReset()
    api.post.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('creates an organisation, then shows cohorts and analytics', async () => {
    let organisations = []
    api.get.mockImplementation((url) => {
      if (url === '/me/organizations') return Promise.resolve({ data: organisations })
      if (url.endsWith('/cohorts')) {
        return Promise.resolve({ data: [{ id: 9, name: 'CSE 2027', department: 'CSE', studentCount: 12, assignmentCount: 2 }] })
      }
      if (url.endsWith('/analytics')) {
        return Promise.resolve({ data: { totalStudents: 12, completionRate: 50, averageScore: 72, weakestCriterion: 'evidence' } })
      }
      if (url.endsWith('/usage')) {
        return Promise.resolve({ data: { plan: 'PILOT', students: 12, cohorts: 1, assignments: 2, interviews: 6 } })
      }
      return Promise.resolve({ data: [] })
    })
    api.post.mockImplementation((url) => {
      if (url === '/orgs') {
        organisations = [{ id: 1, name: 'Saraswati', slug: 'saraswati', plan: 'PILOT', role: 'ADMIN' }]
        return Promise.resolve({ data: organisations[0] })
      }
      return Promise.resolve({ data: {} })
    })

    renderAt(<TpoDashboard />)

    expect(await screen.findByText('Create your first organisation')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Organisation name'), { target: { value: 'Saraswati' } })
    fireEvent.click(screen.getByRole('button', { name: /create organisation/i }))

    await waitFor(() => {
      expect(screen.getByText('CSE 2027')).toBeInTheDocument()
    }, { timeout: 3000 })
    expect(screen.getByText('50%')).toBeInTheDocument()
    expect(screen.getByText('evidence')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/orgs', { name: 'Saraswati' })
  })

  it('shows an assigned interview and starts it', async () => {
    api.get.mockImplementation((url) => {
      if (url === '/interviews') return Promise.resolve({ data: [] })
      if (url === '/practice') return Promise.resolve({ data: [] })
      if (url === '/me/organizations') return Promise.resolve({ data: [] })
      if (url === '/me/assignments') {
        return Promise.resolve({
          data: [{
            assignmentId: 7,
            organizationId: 3,
            organizationName: 'Test College',
            trackName: 'TCS NQT',
            title: 'TCS mock',
            dueAt: null,
            maxAttempts: 1,
            attemptsUsed: 0,
            status: 'ASSIGNED',
            interviewId: null,
            score: null,
            canStart: true,
          }],
        })
      }
      return Promise.resolve({ data: [] })
    })
    api.post.mockResolvedValue({ data: { interviewId: 42 } })

    renderAt(
      <Routes>
        <Route path="/" element={<StudentDashboard />} />
        <Route path="/interview/:id" element={<div>Interview page</div>} />
      </Routes>,
    )

    expect(await screen.findByText('TCS mock')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Start' }))
    expect(await screen.findByText('Interview page')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/orgs/3/assignments/7/start')
  })

  it('saves recording and proctoring consent from the dialog', async () => {
    const onAccepted = vi.fn()
    api.post.mockResolvedValue({
      data: { consentRecording: true, consentProctoring: true, dataRetentionDays: 30 },
    })

    render(
      <ConsentDialog open initial={{ consentProctoring: true }} onAccepted={onAccepted} onDeclined={vi.fn()} />,
    )

    fireEvent.click(screen.getByRole('checkbox', { name: /voice answers/i }))
    fireEvent.click(screen.getByRole('button', { name: /save and continue/i }))

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith('/me/consent', {
        consentRecording: true,
        consentProctoring: true,
      })
    })
    expect(onAccepted).toHaveBeenCalled()
  })
})
