/// <reference types="vite/client" />
// Change requests ("заявки"): the frontend side of the contract with task 1 (API/DB).
// Shapes and endpoints are described in docs/UI_CONTRACT.md. Until the server has
// /requests, the same calls are served by a browser-local mock that is flagged in the UI.
import type { CalendarData, CalendarEvent } from './calendar/PlanningCalendar'

export type RequestStatus = 'RECEIVED' | 'APPLIED' | 'CALCULATED' | 'VALIDATED' | 'APPROVED' | 'REJECTED' | 'FAILED'

export type TripChange = {
  kind: 'TRIP_CHANGE'
  trainId: string
  tripId: string
  newDepartureAt: string // ISO with offset
  newArrivalAt: string
  reason: string
  source: string // who reported it: call from the line, ASU, crew...
}

export type UrgentMaintenance = {
  kind: 'URGENT_MAINTENANCE'
  trainId: string
  problem: string
  detectedAt: string
  notBeforeTripEnd: boolean // work may start only after the current trip arrives
  urgency: 'IMMEDIATE' | 'WITHIN_24H' | 'WITHIN_HORIZON'
  dueBy: string | null
  workType: string
}

export type RequestPayload = TripChange | UrgentMaintenance

export type ChangeRequest = {
  id: string
  number: number
  scenarioId: string
  baseSnapshotHash: string
  status: RequestStatus
  payload: RequestPayload
  comment: string
  createdBy: string
  createdAt: string
  newSnapshotHash: string | null
  jobId: string | null
  planId: string | null
  validationStatus: 'PASS' | 'FAILED' | 'NOT_PERFORMED' | null
  decidedBy: string | null
  decidedAt: string | null
  error: { code: string; message: string } | null
}

export type NewRequest = {
  clientRequestId: string // idempotency: a retried click must not create a second request
  expectedSnapshotHash: string
  payload: RequestPayload
  comment: string
}

export const STATUS_STEPS: { status: RequestStatus; label: string }[] = [
  { status: 'RECEIVED', label: 'Получена' },
  { status: 'APPLIED', label: 'Принята в расчёт' },
  { status: 'CALCULATED', label: 'Рассчитана' },
  { status: 'VALIDATED', label: 'Проверена D2' },
  { status: 'APPROVED', label: 'Согласована' }
]
export const STATUS_LABEL: Record<RequestStatus, string> = {
  RECEIVED: 'Получена', APPLIED: 'Принята в расчёт', CALCULATED: 'Рассчитана', VALIDATED: 'Проверена D2',
  APPROVED: 'Согласована', REJECTED: 'Отклонена', FAILED: 'Ошибка'
}

// ---------- transport: server if it has /requests, otherwise the local mock

const xsrf = () => document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? ''

// Per endpoint: null = not probed yet. Before task 1 ships them the API answers 404
// (unknown path), 400 (/plans/active parsed as an id) or 403 (role rules for unknown paths).
const ready: Record<string, boolean | null> = { requests: null, active: null }
const MISSING = [400, 403, 404, 405]

async function server(feature: string, url: string, init?: RequestInit): Promise<Response | null> {
  if (ready[feature] === false) return null
  const res = await fetch(url, init)
  if (ready[feature] === null && MISSING.includes(res.status)) { ready[feature] = false; return null }
  ready[feature] = true
  if (!res.ok) {
    const text = await res.text()
    let message = text
    try { message = JSON.parse(text).message ?? text } catch { /* plain text */ }
    throw new Error(message || `${res.status}`)
  }
  return res
}

// ponytail: browser-local mock until task 1 ships /requests. It only records the
// request (status RECEIVED); it never pretends a snapshot, calculation or D2 happened.
const KEY = 'okno.mock.requests'
const load = (): ChangeRequest[] => { try { return JSON.parse(localStorage.getItem(KEY) ?? '[]') } catch { return [] } }
const save = (list: ChangeRequest[]) => { try { localStorage.setItem(KEY, JSON.stringify(list)) } catch { /* storage blocked */ } }

export const isMock = () => ready.requests === false

export const requestsApi = {
  list: async (scenarioId?: string): Promise<ChangeRequest[]> => {
    const res = await server('requests', `/api/v1/requests${scenarioId ? `?scenarioId=${scenarioId}` : ''}`)
    if (res) return res.json()
    return load().filter((r) => !scenarioId || r.scenarioId === scenarioId)
  },

  create: async (scenarioId: string, req: NewRequest, user: string): Promise<ChangeRequest> => {
    const res = await server('requests', `/api/v1/scenarios/${scenarioId}/requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf() },
      body: JSON.stringify(req)
    })
    if (res) return res.json()
    const list = load()
    const replay = list.find((r) => r.id === req.clientRequestId)
    if (replay) return replay
    const created: ChangeRequest = {
      id: req.clientRequestId, number: list.reduce((m, r) => Math.max(m, r.number), 0) + 1, scenarioId,
      baseSnapshotHash: req.expectedSnapshotHash, status: 'RECEIVED', payload: req.payload, comment: req.comment,
      createdBy: user, createdAt: new Date().toISOString(), newSnapshotHash: null, jobId: null, planId: null,
      validationStatus: null, decidedBy: null, decidedAt: null, error: null
    }
    save([created, ...list])
    return created
  },

  // Plan everyone except the planner looks at: the approved one, not the newest draft.
  activePlanId: async (): Promise<string | null> => {
    const res = await server('active', '/api/v1/plans/active')
    if (res) return res.status === 204 ? null : (await res.json()).id
    return null
  }
}

// ---------- before/after comparison of two plan calendars

export type Change = {
  kind: 'MOVED' | 'ADDED' | 'REMOVED'
  trainId: string
  label: string
  before: CalendarEvent | null
  after: CalendarEvent | null
}

// Trips keep their id across versions; service blocks are regenerated per run, so
// they are matched by train + cycle + order within that train.
const eventKey = (e: CalendarEvent, i: number) => (e.kind === 'TRIP' ? `trip:${e.id}` : `svc:${e.trainId}:${e.cycleCode ?? e.label}:${i}`)

function keyed(cal: CalendarData | undefined) {
  const map = new Map<string, CalendarEvent>()
  const seen = new Map<string, number>()
  for (const e of [...(cal?.events ?? [])].sort((a, b) => a.startAt.localeCompare(b.startAt))) {
    const base = `${e.trainId}:${e.cycleCode ?? e.label}`
    const i = e.kind === 'TRIP' ? 0 : seen.get(base) ?? 0
    if (e.kind !== 'TRIP') seen.set(base, i + 1)
    map.set(eventKey(e, i), e)
  }
  return map
}

export function diffPlans(before: CalendarData | undefined, after: CalendarData | undefined): Change[] {
  const a = keyed(before)
  const b = keyed(after)
  const out: Change[] = []
  for (const [k, e] of b) {
    const old = a.get(k)
    if (!old) out.push({ kind: 'ADDED', trainId: e.trainId, label: e.label, before: null, after: e })
    else if (Date.parse(old.startAt) !== Date.parse(e.startAt) || Date.parse(old.endAt) !== Date.parse(e.endAt))
      out.push({ kind: 'MOVED', trainId: e.trainId, label: e.label, before: old, after: e })
  }
  for (const [k, e] of a) if (!b.has(k)) out.push({ kind: 'REMOVED', trainId: e.trainId, label: e.label, before: e, after: null })
  return out.sort((x, y) => (x.after ?? x.before)!.startAt.localeCompare((y.after ?? y.before)!.startAt))
}

// Self-check in dev builds: a moved trip, a regenerated-but-unchanged service block
// (new id, same train/cycle/time) and a removed block must come out as MOVED / — / REMOVED.
if (import.meta.env.DEV) {
  const ev = (id: string, kind: 'TRIP' | 'SERVICE', start: string, cycleCode?: string) =>
    ({ id, kind, trainId: 'T', label: id, startAt: start, endAt: start.replace(':00Z', ':30Z'), source: '', reason: '', cycleCode })
  const cal = (events: CalendarEvent[]) => ({ events } as unknown as CalendarData)
  const d = diffPlans(
    cal([ev('r1', 'TRIP', '2028-07-01T00:00Z'), ev('s1', 'SERVICE', '2028-07-01T02:00Z', 'IS100'), ev('s2', 'SERVICE', '2028-07-02T02:00Z', 'IS200')]),
    cal([ev('r1', 'TRIP', '2028-07-01T00:05Z'), ev('s9', 'SERVICE', '2028-07-01T02:00Z', 'IS100')]))
  console.assert(d.length === 2 && d[0].kind === 'MOVED' && d[1].kind === 'REMOVED', 'diffPlans self-check failed', d)
}
