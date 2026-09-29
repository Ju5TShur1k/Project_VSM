export type Train = {
  id: string
  externalId: string
  status: string
  mileageKm: number
  nextObligation: string
}

export type Validation = { code: string; severity: string; message: string; objectId?: string | null; startAt?: string | null; endAt?: string | null }

export type ValidationReport = {
  schemaVersion: string
  status: 'PASS' | 'FAILED' | 'NOT_PERFORMED'
  scope: string
  scenarioId: string
  sourceSnapshotId: string | null
  snapshotHash: string
  resultHash: string
  policy: string
  solverStatus: string
  planVersion: number
  checkedAt: string
  ruleVersion: string | null
  ruleConfirmation: string | null
  requiredServiceCount: number | null
  pendingMilestones: { trainId: string; cycleCode: string; nominalKm: number; remainingKm: number }[]
  findings: Validation[]
}

export type PlanMetrics = {
  scope: string
  scheduledTripCount: number
  conflictingTripCount: number
  requiredServiceCount: number
  placedServiceCount: number
  missingServiceCount: number
  trainServiceMinutes: number
  makespanMinutes: number
  peakConcurrentService: number
  resourceLoads: { resourceId: string; busyMinutes: number; horizonSharePercent: number }[]
}

export type PlanEvent = {
  id: string
  trainId: string
  kind: string
  startAt: string
  endAt: string
  resourceIds: string[]
}

export type Plan = {
  id: string
  scenarioId: string
  version: number
  status: string
  approvedBy: string | null
  events: PlanEvent[]
  validations: Validation[]
  snapshotHash: string
  validationStatus: 'PASS' | 'FAILED' | 'NOT_PERFORMED'
  validationReport?: ValidationReport | null
  metrics?: PlanMetrics | null
}

export type DemoSource = {
  scenarioId: string
  snapshotId: string
  snapshotHash: string
  provenance: string
}

export type CaseDataset = {
  source: DemoSource
  dataset: 'FULL43' | 'E2_6' | 'BLOCKED6'
  trainCount: number
  tripCount: number
  planningSupported: boolean
  warnings: string[]
}

export type RecoveryBoard = {
  id: string; version: number; stateHash: string; scenarioId: string; sourceSnapshotId: string; snapshotHash: string
  scope: string; provenance: string; windowStart: string; windowEnd: string; asOf: string
  policy: { version: string; preparationMinutes: number; targetReservePerCity: number; cleaningEveryTrips: number; source: string }
  trainCount: number; availableTrainCount: number; unavailableTrainCount: number; uncoveredTripCount: number; incompletePairCount: number
  reserve: { location: string; available: number; target: number; deficit: number }[]
  trains: { id: string; externalId: string; status: string; location: string; mileageKm: number; tripsSinceCleaning: number | null; readinessEvidence: string }[]
  trips: { id: string; label: string; pairKey: string; plannedTrainId: string; plannedTrain: string; effectiveTrainId: string; effectiveTrain: string; origin: string; destination: string; departureAt: string; arrivalAt: string; coverage: string }[]
  faults: { id: string; trainId: string; train: string; trip: string; occurredAt: string; expectedRepairAt: string | null; description: string; status: string; affectedTripIds: string[]; replacementTrain: string | null; candidates: { trainId: string; externalId: string; location: string; eligible: boolean; reasons: string[] }[]; acceptedAt: string | null }[]
  messages: string[]
}

export type Job = {
  jobId: string
  status: string
  solverStatus: string | null
  planId: string | null
  error: string | null
}

export class Unauthorized extends Error {
  constructor() {
    super('Сессия истекла, войдите заново')
  }
}

// A concurrent plan or operational decision changed the version we loaded.
export class Conflict extends Error {
  constructor() {
    super('Данные уже изменены — загружена актуальная версия, проверьте решение и повторите действие')
  }
}

async function json<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new Unauthorized()
  if (res.status === 409) throw new Conflict()
  if (!res.ok) throw new Error(await errorMessage(res))
  return res.json()
}

// API errors are {code,message,...}; fall back to the raw text for anything else.
async function errorMessage(res: Response) {
  const text = await res.text()
  try {
    return JSON.parse(text).message ?? text
  } catch {
    return `${res.status} ${text}`
  }
}

// The API sets an XSRF-TOKEN cookie on its first response (even the 401 of
// /auth/me); state-changing requests must echo it back in this header.
const xsrf = () => document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? ''

const JSON_HEADERS = { 'Content-Type': 'application/json' }

const post = (url: string, init: RequestInit = {}) =>
  fetch(url, { method: 'POST', ...init, headers: { 'X-XSRF-TOKEN': xsrf(), ...init.headers } })

export const api = {
  me: () => fetch('/api/v1/auth/me').then(json<{ username: string }>),

  login: async (username: string, password: string) => {
    const res = await post('/api/v1/auth/login', { body: new URLSearchParams({ username, password }) })
    if (res.status === 401) throw new Error('Неверный логин или пароль')
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  },

  logout: () => post('/api/v1/auth/logout'),

  importScenario: () =>
    post('/api/v1/scenarios/import', {
      headers: JSON_HEADERS,
      body: '{}'
    }).then(json<{ scenarioId: string; warnings: string[]; provenance: string }>),

  importDemoSource: () => post('/api/v1/demo/source').then(json<DemoSource>),
  importCaseDataset: (dataset: CaseDataset['dataset']) =>
    post(`/api/v1/demo/case-source?dataset=${dataset}`).then(json<CaseDataset>),

  createRecovery: (scenarioId: string) => post(`/api/v1/scenarios/${scenarioId}/operations`).then(json<RecoveryBoard>),
  getRecovery: (id: string) => fetch(`/api/v1/operations/${id}`).then(json<RecoveryBoard>),
  recoveryCommand: (board: RecoveryBoard, command: 'failures' | 'replacements' | 'releases' | 'clock', body: Record<string, unknown>) =>
    post(`/api/v1/operations/${board.id}/${command}`, { headers: JSON_HEADERS, body: JSON.stringify({ expectedVersion: board.version, ...body }) }).then(json<RecoveryBoard>),

  changeR1Arrival: (scenarioId: string, arrivalMinute: number) =>
    post(`/api/v1/demo/scenarios/${scenarioId}/r1-arrival`, {
      headers: JSON_HEADERS,
      body: JSON.stringify({ arrivalMinute })
    }).then(json<DemoSource>),

  getTrains: (scenarioId: string) =>
    fetch(`/api/v1/scenarios/${scenarioId}/trains`).then(json<Train[]>),

  getScenario: (id: string) => fetch(`/api/v1/scenarios/${id}`).then(json<{ provenance: string }>),

  // Injects a failure as a NEW scenario version; the old one stays untouched.
  addEvent: (scenarioId: string, kind: string, description: string) =>
    post(`/api/v1/scenarios/${scenarioId}/events`, {
      headers: JSON_HEADERS,
      body: JSON.stringify({ kind, description })
    }).then(json<{ newScenarioId: string }>),

  // A fresh idempotencyKey per click = a new job; retries of the same click would reuse it.
  startJob: (scenarioId: string) =>
    post('/api/v1/planning-jobs', {
      headers: JSON_HEADERS,
      body: JSON.stringify({
        scenarioId,
        policy: 'BLOCKS_CP_SAT',
        seed: 42,
        timeLimitSec: 30,
        idempotencyKey: crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}` // randomUUID needs https/localhost
      })
    }).then(json<Job>),

  getJob: (id: string) => fetch(`/api/v1/planning-jobs/${id}`).then(json<Job>),

  getPlan: (id: string) => fetch(`/api/v1/plans/${id}`).then(json<Plan>),

  getCalendar: (id: string) => fetch(`/api/v1/plans/${id}/calendar`).then(json<import('./calendar/PlanningCalendar').CalendarData>),

  // The approver is taken from the session server-side, so no actorId is sent.
  approve: (planId: string, expectedVersion: number, comment: string) =>
    post(`/api/v1/plans/${planId}/approve`, {
      headers: JSON_HEADERS,
      body: JSON.stringify({ expectedVersion, comment })
    }).then(json<Plan>)
}
