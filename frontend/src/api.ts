import type { CalendarData } from './calendar/PlanningCalendar'

export type Role = 'PLANNER' | 'TECHNOLOGIST' | 'DISPATCHER'

// One immutable version of the fleet's source data (trips, trains, rules).
export type Version = {
  rootId: string
  scenarioId: string
  version: number
  snapshotId: string
  snapshotHash: string
  createdBy: string
  createdAt: string
}

// Saved source payload of one data version (only the parts the UI reads).
export type SourcePayload = {
  scenario: { horizon_start: string; horizon_end: string }
  trains: { id: string; external_id: string; status: string; location: string }[]
  fixedTrips: { id: string; train_id: string; label: string; origin: string; destination: string; departure_at: string; arrival_at: string }[]
}

export type Plan = {
  id: string
  scenarioId: string
  status: string
  snapshotHash: string
  validationStatus: 'PASS' | 'FAILED' | 'NOT_PERFORMED'
}

// Result of building the 14-day maintenance plan for the whole fleet.
export type FullDraft = {
  planId: string | null
  snapshotHash: string
  searchStatus: string
  moves: number
  tripCount: number
  changedTripCount: number
  requiredCleaningCount: number
  placedBlockCount: number
  modelSolverStatus: string
  structuralStatus: 'PASS' | 'FAILED' | 'NOT_PERFORMED'
  d2Status: string
  diagnostics: { code: string; message: string }[]
  reserve: { mobilizedTrainCount: number; cities: { name: string; sourceTarget: number; remaining: number; deficit: number }[] }
}

export type ProposalPayload =
  | { kind: 'TRIP_CHANGE'; trainId: string; tripId: string; newDepartureAt: string; newArrivalAt: string; reason: string; source: string }
  | { kind: 'TRAIN_FAILURE'; trainId: string; tripId: string; occurredAt: string; expectedRepairAt: string | null; description: string; operationsId: string }

export type Proposal = {
  id: string
  number: number
  scenarioId: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  payload: ProposalPayload
  comment: string
  createdBy: string
  createdAt: string
  reviewedBy: string | null
  reviewedAt: string | null
  reviewComment: string | null
  applied: { newSnapshotHash: string; snapshotId: string; sourceVersion: number } | null
}

export type RecoveryBoard = {
  id: string; version: number; scenarioId: string; asOf: string; windowStart: string; windowEnd: string
  reserve: { location: string; available: number; target: number; deficit: number }[]
  trains: { id: string; externalId: string; status: string; location: string }[]
  trips: { id: string; label: string; effectiveTrainId: string; effectiveTrain: string; origin: string; destination: string; departureAt: string; arrivalAt: string; coverage: string }[]
  faults: { id: string; trainId: string; train: string; trip: string; occurredAt: string; description: string; status: string; affectedTripIds: string[]; replacementTrain: string | null; candidates: { trainId: string; externalId: string; location: string; eligible: boolean; reasons: string[] }[] }[]
  uncoveredTripCount: number
}

export class Unauthorized extends Error {
  constructor() { super('Сессия истекла, войдите заново') }
}

// Someone else changed the same data or decision first.
export class Conflict extends Error {
  constructor() { super('Данные уже изменены другим пользователем — обновлено, повторите действие') }
}

async function json<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new Unauthorized()
  if (res.status === 409) throw new Conflict()
  if (!res.ok) {
    const text = await res.text()
    let message = text
    try { message = JSON.parse(text).message ?? text } catch { /* plain text */ }
    throw new Error(message || `${res.status}`)
  }
  return res.json()
}

// State-changing requests echo the XSRF-TOKEN cookie back in this header.
const xsrf = () => document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? ''
const post = (url: string, body?: unknown, contentType = 'application/json') =>
  fetch(url, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': xsrf(), ...(body === undefined ? {} : { 'Content-Type': contentType }) },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body)
  })
const get = <T,>(url: string) => fetch(url).then(json<T>)
export const newId = () => crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`

export const api = {
  me: () => get<{ username: string; role: Role }>('/api/v1/auth/me'),
  login: async (username: string, password: string) => {
    const res = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'X-XSRF-TOKEN': xsrf() }, body: new URLSearchParams({ username, password }) })
    if (res.status === 401) throw new Error('Неверный логин или пароль')
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  },
  logout: () => post('/api/v1/auth/logout'),

  // Registers the 43-train fleet as source data and opens today's operations board.
  loadFleet: async () => {
    const loaded = await post('/api/v1/demo/case-source?dataset=FULL43').then(json<{ source: { scenarioId: string } }>)
    const version = await get<Version>(`/api/v1/scenarios/${loaded.source.scenarioId}/version`)
    const board = await post(`/api/v1/scenarios/${loaded.source.scenarioId}/operations`).then(json<RecoveryBoard>)
    return { rootId: version.rootId, operationsId: board.id }
  },
  head: (rootId: string) => get<Version>(`/api/v1/scenarios/${rootId}/version`),
  // Newest plan of this data across versions (the server clears "latest draft" on every data change).
  lastPlan: (rootId: string) => get<{ planId: string | null }>(`/api/v1/scenarios/${rootId}/last-plan`),
  source: (scenarioId: string) => get<SourcePayload>(`/api/v1/scenarios/${scenarioId}/source`),
  uploadSchedule: (rootId: string, csv: string) =>
    post(`/api/v1/scenarios/${rootId}/schedule`, csv, 'text/csv; charset=utf-8')
      .then(json<{ changed: number; added: number; unchanged: number }>),

  buildPlan: (snapshotId: string, frozenUntil: string) =>
    post(`/api/v1/source-snapshots/${snapshotId}/e3-full-draft`, {
      frozenUntil, preparationMinutes: 30, maxMoves: 20, maxEvaluationsPerMove: 32, seed: 1, timeLimitSec: 60
    }).then(json<FullDraft>),
  plan: (id: string) => get<Plan>(`/api/v1/plans/${id}`),
  calendar: (id: string) => get<CalendarData>(`/api/v1/plans/${id}/calendar`),

  proposals: (rootId?: string) => get<Proposal[]>(`/api/v1/proposals${rootId ? `?scenarioId=${rootId}` : ''}`),
  propose: (rootId: string, payload: ProposalPayload, comment: string, clientRequestId: string) =>
    post(`/api/v1/scenarios/${rootId}/proposals`, { clientRequestId, expectedSnapshotHash: '', payload, comment }).then(json<Proposal>),
  approveProposal: (id: string, comment: string) => post(`/api/v1/proposals/${id}/approve`, { comment }).then(json<Proposal>),
  rejectProposal: (id: string, comment: string) => post(`/api/v1/proposals/${id}/reject`, { comment }).then(json<Proposal>),

  openBoard: (scenarioId: string) => post(`/api/v1/scenarios/${scenarioId}/operations`).then(json<RecoveryBoard>),
  board: (id: string) => get<RecoveryBoard>(`/api/v1/operations/${id}`),
  boardCommand: (board: RecoveryBoard, command: 'failures' | 'replacements', body: Record<string, unknown>) =>
    post(`/api/v1/operations/${board.id}/${command}`, { expectedVersion: board.version, ...body }).then(json<RecoveryBoard>)
}

// Codes from the API as the user should read them.
const RU: Record<string, string> = {
  OPTIMAL: 'оптимальный', FEASIBLE: 'допустимый', INFEASIBLE: 'нет решения', UNKNOWN: 'не найдено', NOT_RUN: 'не запускался',
  PASS: 'пройдена', FAILED: 'не пройдена', NOT_PERFORMED: 'не выполнена',
  PENDING: 'Ждёт диспетчера', APPROVED: 'Согласована', REJECTED: 'Отклонена',
  TRIP_CHANGE: 'Изменение рейса', TRAIN_FAILURE: 'Отказ состава',
  MOSCOW: 'Москва', SPB_DEPOT: 'Санкт-Петербург', LINE: 'На линии', RESERVE: 'Резерв', MAINTENANCE: 'В депо', AVAILABLE: 'На линии'
}
export const ru = (code: string | null | undefined) => (code ? RU[code] ?? code : '—')
