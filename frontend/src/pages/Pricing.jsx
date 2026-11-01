import { Link } from 'react-router-dom'
import { ArrowLeft, Check } from 'lucide-react'

const PLANS = [
  {
    name: 'Pilot',
    price: 'Free',
    cadence: '2 weeks · one department',
    features: ['Up to 100 students', 'Company-track interviews', 'Readiness analytics', 'Student data controls'],
    cta: 'Start a pilot',
  },
  {
    name: 'Starter',
    price: '₹50,000',
    cadence: 'per year',
    features: ['Up to 500 students', 'All company tracks', 'Cohort analytics + CSV export', 'Email invites', 'Priority support'],
    cta: 'Choose Starter',
  },
  {
    name: 'Growth',
    price: '₹1,50,000',
    cadence: 'per year',
    features: ['Unlimited students', 'Advanced analytics', 'Custom tracks', 'Data retention controls', 'Onboarding workshop'],
    cta: 'Talk to us',
  },
]

const Pricing = () => (
  <div className="min-h-screen" style={{ background: '#0a0a0a' }}>
    <header className="border-b" style={{ borderColor: 'rgba(255,255,255,.08)' }}>
      <div className="max-w-5xl mx-auto px-6 py-5 flex items-center justify-between">
        <Link to="/" className="flex items-center gap-2.5">
          <span className="w-8 h-8 rounded-xl flex items-center justify-center text-white font-bold text-sm"
            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}>AI</span>
          <span className="font-bold text-white">PlacementOS</span>
        </Link>
        <Link to="/" className="inline-flex items-center gap-1.5 text-sm" style={{ color: 'rgba(255,255,255,.74)' }}>
          <ArrowLeft className="h-4 w-4" /> Back to app
        </Link>
      </div>
    </header>

    <main className="max-w-5xl mx-auto px-6 py-12">
      <h1 className="text-4xl font-bold mb-2" style={{ color: '#fff' }}>Simple pricing for placement cells</h1>
      <p className="mb-10 text-sm" style={{ color: 'rgba(255,255,255,.66)' }}>
        No payment gateway is needed for the pilot. Paid plans are invoiced annually.
      </p>

      <div className="grid gap-5 md:grid-cols-3">
        {PLANS.map((plan) => (
          <div key={plan.name} className="p-6 rounded-2xl flex flex-col"
            style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.1)' }}>
            <h2 className="text-lg font-semibold" style={{ color: '#fff' }}>{plan.name}</h2>
            <p className="mt-2 text-3xl font-bold" style={{ color: '#fff' }}>{plan.price}</p>
            <p className="text-sm mb-5" style={{ color: 'rgba(255,255,255,.6)' }}>{plan.cadence}</p>
            <ul className="space-y-2 text-sm flex-1" style={{ color: 'rgba(255,255,255,.76)' }}>
              {plan.features.map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <Check className="h-4 w-4 mt-0.5" style={{ color: '#4ade80' }} /> {feature}
                </li>
              ))}
            </ul>
            <Link to="/register" className="mt-6 inline-flex justify-center px-4 py-2.5 rounded-xl text-sm font-medium"
              style={{ background: '#f5f5f4', color: '#111' }}>
              {plan.cta}
            </Link>
          </div>
        ))}
      </div>

      <p className="mt-10 text-xs" style={{ color: 'rgba(255,255,255,.5)' }}>
        Pilot success criteria, timeline and invoice details are in <code>docs/PILOT.md</code>.
      </p>
    </main>
  </div>
)

export default Pricing
