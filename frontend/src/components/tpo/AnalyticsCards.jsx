const DEFAULTS = [
  { key: 'completionRate', label: 'Completion rate', suffix: '%', hint: 'Completed attempts ÷ expected attempts' },
  { key: 'averageScore', label: 'Average score', suffix: '%', hint: 'Across evaluated answers' },
  { key: 'totalStudents', label: 'Students', suffix: '', hint: 'Enrolled in this organization' },
  { key: 'weakestCriterion', label: 'Weakest criterion', suffix: '', hint: 'Lowest average rubric criterion' },
]

const pretty = (value) => {
  if (value == null || value === '') return '—'
  if (value === 'technical_depth') return 'Technical depth'
  return String(value).replace(/_/g, ' ')
}

const AnalyticsCards = ({ analytics, loading = false }) => {
  if (loading) {
    return (
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-28 rounded-2xl animate-pulse" style={{ background: 'rgba(255,255,255,.06)' }} />
        ))}
      </div>
    )
  }

  const criteria = analytics?.averageScoresByCriterion || {}
  const distribution = analytics?.scoreDistribution || []
  const maxBucket = Math.max(1, ...distribution.map((item) => item.count))

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {DEFAULTS.map((card) => {
          const value = analytics?.[card.key]
          return (
            <div key={card.key} className="p-5 rounded-2xl" style={{ background: 'rgba(255,255,255,.04)', border: '1px solid rgba(255,255,255,.1)' }}>
              <p className="text-2xl font-bold" style={{ color: '#fff' }}>
                {pretty(value)}{value != null && value !== '' ? card.suffix : ''}
              </p>
              <p className="text-sm mt-1" style={{ color: 'rgba(255,255,255,.74)' }}>{card.label}</p>
              <p className="text-xs mt-2" style={{ color: 'rgba(255,255,255,.5)' }}>{card.hint}</p>
            </div>
          )
        })}
      </div>

      <div className="grid md:grid-cols-2 gap-4">
        <div className="p-4 rounded-2xl" style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.08)' }}>
          <p className="text-sm font-medium mb-3" style={{ color: '#fff' }}>Average by criterion</p>
          {Object.keys(criteria).length === 0 ? (
            <p className="text-xs" style={{ color: 'rgba(255,255,255,.5)' }}>No evaluated answers yet.</p>
          ) : (
            <div className="space-y-2">
              {Object.entries(criteria).map(([criterion, value]) => (
                <div key={criterion} className="flex items-center gap-3">
                  <span className="text-xs w-28 capitalize" style={{ color: 'rgba(255,255,255,.66)' }}>
                    {criterion.replace(/([A-Z])/g, ' $1')}
                  </span>
                  <div className="flex-1 h-1.5 rounded-full" style={{ background: 'rgba(255,255,255,.09)' }}>
                    <div className="h-full rounded-full" style={{ width: `${Math.min(100, Number(value) || 0)}%`, background: '#fbbf24' }} />
                  </div>
                  <span className="text-xs w-8 text-right" style={{ color: '#fff' }}>{value}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="p-4 rounded-2xl" style={{ background: 'rgba(255,255,255,.03)', border: '1px solid rgba(255,255,255,.08)' }}>
          <p className="text-sm font-medium mb-3" style={{ color: '#fff' }}>Score distribution</p>
          <div className="flex items-end gap-3 h-24">
            {distribution.map((bucket) => (
              <div key={bucket.band} className="flex-1 flex flex-col items-center gap-1">
                <div className="w-full rounded-t" style={{
                  height: `${Math.max(3, (bucket.count / maxBucket) * 64)}px`,
                  background: 'linear-gradient(180deg, #fbbf24, rgba(251,191,36,.3))',
                }} />
                <span className="text-[10px]" style={{ color: 'rgba(255,255,255,.55)' }}>{bucket.band}</span>
                <span className="text-xs" style={{ color: '#fff' }}>{bucket.count}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

export default AnalyticsCards
