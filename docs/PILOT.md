# PlacementOS pilot package

## Offer

| Plan | Price | Limits |
|---|---|---|
| Pilot | Free, 2 weeks | 1 department, up to 100 students |
| Starter | ₹50,000 / year | up to 500 students |
| Growth | ₹1,50,000 / year | unlimited students, advanced analytics, custom tracks |

No payment gateway is needed for the pilot. Paid plans are invoiced annually.

## Timeline (2 weeks)

| Day | Activity | Owner |
|---|---|---|
| 1 | Kickoff, department + graduation-year selection, TPO admin accounts | PlacementOS |
| 1-2 | Import the student CSV, fix rejected rows, send invites | TPO + PlacementOS |
| 2 | Configure one company track (e.g. TCS NQT) and one practice assignment | TPO |
| 3-9 | Students take interviews; TPO watches completion and weakest-criterion analytics | TPO |
| 9 | Mid-pilot review: completion rate, score distribution, data-control checks | Both |
| 10 | Final review, export CSV, decide Starter/Growth | Both |
| 10 | Delete or retain pilot data per the signed consent | PlacementOS |

## Deliverables

- One organisation with an ADMIN/TPO membership for the placement cell.
- One cohort with up to 100 enrolled students and working sign-in.
- One company-track assignment with a due date and attempt limit.
- TPO dashboard with completion rate, average score per rubric criterion,
  weakest criterion, score distribution and attempts per student.
- Per-student evidence-grounded report and practice plan.
- CSV export of all attempts and scores.
- Consent, data export and deletion flows demonstrated on a test student.

## Success criteria

| Metric | Target |
|---|---|
| Students onboarded | ≥ 80 of 100 |
| Interviews completed | ≥ 60% of assigned students |
| Median time to first report | < 10 minutes after the last answer |
| TPO can self-serve cohort + assignment | Yes, without engineering help |
| Data deletion request completed | < 1 business day, confirmed |
| Rubric quote grounding | 100% of cited quotes verified |
| Paid conversion decision | Documented at day 10 |

## Invoice details

Fill these in before issuing an invoice:

- Legal entity: _TBD_
- GSTIN: _TBD_
- Bank account / UPI: _TBD_
- Invoice prefix: `POS-`
- Payment terms: 15 days from invoice
- Support channel: _TBD_

## Data handling during the pilot

- Recordings are opt-in and never stored in the database.
- Retention is 30 days by default and configurable per student (1-365).
- The TPO gets an aggregate CSV; raw audio is not part of the export.
- A student can delete interviews, recordings and resumes from Profile → Privacy.
- The retention sweep runs as a dry run until the college signs off on deletion.
