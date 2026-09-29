/// <reference types="vite/client" />
import type { CalendarData, CalendarEvent } from './calendar/PlanningCalendar'

export type Change = {
  kind: 'MOVED' | 'REASSIGNED' | 'ADDED' | 'REMOVED'
  trainId: string
  label: string
  before: CalendarEvent | null
  after: CalendarEvent | null
}

// Trips keep their id across versions; maintenance/cleaning blocks are regenerated per
// run, so they are matched by train + work + order within that train.
function keyed(cal: CalendarData | undefined) {
  const map = new Map<string, CalendarEvent>()
  const seen = new Map<string, number>()
  for (const e of [...(cal?.events ?? [])].sort((a, b) => a.startAt.localeCompare(b.startAt))) {
    if (e.kind === 'TRIP') { map.set(`trip:${e.id}`, e); continue }
    const base = `${e.trainId}:${e.cycleCode ?? e.label}`
    const i = seen.get(base) ?? 0
    seen.set(base, i + 1)
    map.set(`svc:${base}:${i}`, e)
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
    else if (old.trainId !== e.trainId) out.push({ kind: 'REASSIGNED', trainId: e.trainId, label: e.label, before: old, after: e })
    else if (Date.parse(old.startAt) !== Date.parse(e.startAt) || Date.parse(old.endAt) !== Date.parse(e.endAt))
      out.push({ kind: 'MOVED', trainId: e.trainId, label: e.label, before: old, after: e })
  }
  for (const [k, e] of a) if (!b.has(k)) out.push({ kind: 'REMOVED', trainId: e.trainId, label: e.label, before: e, after: null })
  return out.sort((x, y) => (x.after ?? x.before)!.startAt.localeCompare((y.after ?? y.before)!.startAt))
}

// Dev self-check: a moved trip, a trip taken over by another train, a regenerated but
// unchanged block (new id, same place) and a removed block.
if (import.meta.env.DEV) {
  const ev = (id: string, kind: 'TRIP' | 'SERVICE', start: string, trainId = 'T', cycleCode?: string) =>
    ({ id, kind, trainId, label: id, startAt: start, endAt: start.replace(':00Z', ':30Z'), source: '', reason: '', cycleCode })
  const cal = (events: CalendarEvent[]) => ({ events } as unknown as CalendarData)
  const d = diffPlans(
    cal([ev('r1', 'TRIP', '2031-07-01T00:00Z'), ev('r2', 'TRIP', '2031-07-01T01:00Z'), ev('s1', 'SERVICE', '2031-07-01T02:00Z', 'T', 'IS100'), ev('s2', 'SERVICE', '2031-07-02T02:00Z', 'T', 'IS200')]),
    cal([ev('r1', 'TRIP', '2031-07-01T00:05Z'), ev('r2', 'TRIP', '2031-07-01T01:00Z', 'R'), ev('s9', 'SERVICE', '2031-07-01T02:00Z', 'T', 'IS100')]))
  console.assert(d.map((c) => c.kind).join() === 'MOVED,REASSIGNED,REMOVED', 'diffPlans self-check failed', d)
}
