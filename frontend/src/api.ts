export type Train = {
  id: string
  externalId: string
  status: string
  mileageKm: number
  nextObligation: string
}

export type Validation = { code: string; severity: string; message: string }

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

// 409 from approve: someone approved/changed the plan since we loaded it.
export class Conflict extends Error {
  constructor() {
    super('План уже изменён — загружена актуальная версия, проверьте и согласуйте снова')
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

  // The approver is taken from the session server-side, so no actorId is sent.
  approve: (planId: string, expectedVersion: number, comment: string) =>
    post(`/api/v1/plans/${planId}/approve`, {
      headers: JSON_HEADERS,
      body: JSON.stringify({ expectedVersion, comment })
    }).then(json<Plan>)
}
