import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ru } from './api'
import type { CalendarData } from './calendar/PlanningCalendar'
import PlanningCalendar from './calendar/PlanningCalendar'
import { Icon } from './Icons'
import { ChangeRequest, diffPlans, isMock, requestsApi, STATUS_LABEL, STATUS_STEPS } from './changeRequests'

export const time = (iso: string) =>
  new Date(iso).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
export const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString('ru-RU', { timeZone: 'Europe/Moscow', hour: '2-digit', minute: '2-digit' })
// 1 изменение, 2 изменения, 5 изменений, 21 изменение
const plural = (n: number, one: string, few: string, many: string) =>
  `${n} ${n % 10 === 1 && n % 100 !== 11 ? one : [2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100) ? few : many}`
const short = (hash: string | null) => (hash ? hash.slice(0, 12) : '—')

// Which plan a viewer looks at: the approved (active) one when the server can say so,
// else the last plan approved in this browser, else the planner's own latest calculation,
// else the newest plan of any scenario. activePlanId is the base for before/after.
export function usePlanView(ownPlanId?: string, approvedHere?: string) {
  const active = useQuery({ queryKey: ['active-plan'], queryFn: requestsApi.activePlanId, refetchInterval: 15_000 })
  const latest = useQuery({ queryKey: ['current-plan'], queryFn: api.currentPlan, refetchInterval: 15_000,
    enabled: active.isFetched && !active.data && !ownPlanId && !approvedHere })
  const activePlanId = active.data ?? approvedHere ?? null
  const planId = activePlanId ?? ownPlanId ?? latest.data?.planId ?? undefined
  const plan = useQuery({ queryKey: ['plan', planId], queryFn: () => api.getPlan(planId!), enabled: !!planId })
  const calendar = useQuery({ queryKey: ['calendar', planId], queryFn: () => api.getCalendar(planId!), enabled: !!planId })
  return { planId, activePlanId, plan: plan.data, calendar: calendar.data }
}

export function PlanBadge({ status, isActive }: { status?: string; isActive: boolean }) {
  if (!status) return null
  return <span className={`badge ${status}`}>{isActive ? 'Действующий план' : ru(status)}</span>
}

export function MockNotice() {
  return isMock() ? <p className="mock-note">Сервер заявок ещё не подключён: заявки хранятся в этом браузере и дальше «Получена» не продвигаются.</p> : null
}

// One-line human summary of a request, using trip labels/times from the plan when known.
export function summary(r: ChangeRequest, cal?: CalendarData) {
  const train = cal?.trains.find((t) => t.id === r.payload.trainId)?.label ?? r.payload.trainId.slice(0, 8)
  if (r.payload.kind === 'TRIP_CHANGE') {
    const p = r.payload
    const trip = cal?.events.find((e) => e.id === p.tripId)
    const was = trip ? `${clock(trip.startAt)}–${clock(trip.endAt)} → ` : ''
    return `${train}: рейс ${trip?.label.replace(/^Рейс /, '').split(' · ')[0] ?? ''} ${was}${clock(p.newDepartureAt)}–${clock(p.newArrivalAt)}`
  }
  return `${train}: срочное ТО — ${r.payload.problem}`
}

export function RequestList({ scenarioId, calendar, selectedId, onSelect }: {
  scenarioId?: string
  calendar?: CalendarData
  selectedId?: string | null
  onSelect?: (r: ChangeRequest) => void
}) {
  const list = useQuery({ queryKey: ['requests', scenarioId], queryFn: () => requestsApi.list(scenarioId), refetchInterval: 5_000 })
  if (list.isError) return <p className="error empty">{list.error.message}</p>
  if (!list.data?.length) return <p className="muted empty">Заявок нет.</p>
  return (
    <div className="table-scroll">
      <table>
        <thead><tr><th>№</th><th>Время</th><th>Заявка</th><th>Статус</th><th>Автор</th></tr></thead>
        <tbody>
          {list.data.map((r) => (
            <tr key={r.id} className={`${onSelect ? 'clickable' : ''} ${selectedId === r.id ? 'current' : ''}`} onClick={() => onSelect?.(r)}>
              <td>{r.number}</td>
              <td>{time(r.createdAt)}</td>
              <td>
                <span className={`badge ${r.payload.kind === 'TRIP_CHANGE' ? 'WARNING' : 'CRITICAL'}`}>{ru(r.payload.kind)}</span>{' '}
                {summary(r, calendar)}
              </td>
              <td><span className={`badge ${r.status}`}>{STATUS_LABEL[r.status]}</span></td>
              <td>{r.createdBy}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// Request № → new snapshot → calculation → D2 → human decision.
export function RequestChain({ r }: { r: ChangeRequest }) {
  const reached = STATUS_STEPS.findIndex((s) => s.status === r.status)
  const detail: Record<string, string> = {
    RECEIVED: `${time(r.createdAt)} · ${r.createdBy} · версия данных ${short(r.baseSnapshotHash)}`,
    APPLIED: r.newSnapshotHash ? `новая версия данных ${short(r.newSnapshotHash)}` : '',
    CALCULATED: r.jobId ? `расчёт ${r.jobId.slice(0, 8)}` : '',
    VALIDATED: r.validationStatus ? `D2: ${ru(r.validationStatus)}` : '',
    APPROVED: r.decidedBy ? `${r.decidedBy}, ${time(r.decidedAt!)}` : ''
  }
  return (
    <ol className="chain">
      {STATUS_STEPS.map((s, i) => (
        <li key={s.status} className={r.status === 'FAILED' || r.status === 'REJECTED' ? (i <= reached ? 'done' : 'failed') : i <= reached ? 'done' : ''}>
          <strong>{s.label}</strong>
          <span>{detail[s.status] || '—'}</span>
        </li>
      ))}
      {r.error && <li className="failed"><strong>{STATUS_LABEL[r.status]}</strong><span>{r.error.message}</span></li>}
    </ol>
  )
}

// Before/after of two plans: only changed trips and maintenance, and the calendar with them highlighted.
export function PlanCompare({ beforeId, afterId }: { beforeId: string; afterId: string }) {
  const before = useQuery({ queryKey: ['calendar', beforeId], queryFn: () => api.getCalendar(beforeId) })
  const after = useQuery({ queryKey: ['calendar', afterId], queryFn: () => api.getCalendar(afterId) })
  const changes = useMemo(() => diffPlans(before.data, after.data), [before.data, after.data])
  const [onlyChanged, setOnlyChanged] = useState(true)
  if (!before.data || !after.data) return <p className="muted">Загрузка сравнения…</p>
  if (before.data.scenarioId !== after.data.scenarioId)
    return <p className="muted">Планы относятся к разным наборам данных — сравнение по рейсам невозможно.</p>
  const trainName = (id: string) => after.data!.trains.find((t) => t.id === id)?.label ?? id
  const changedTrains = new Set(changes.map((c) => c.trainId))
  const kind = { MOVED: 'Сдвиг', ADDED: 'Новое', REMOVED: 'Удалено' }
  return (
    <>
      <h3>Было → стало: {changes.length ? `${plural(changes.length, 'изменение', 'изменения', 'изменений')} у ${changedTrains.size} ${changedTrains.size === 1 ? 'состава' : 'составов'}` : 'изменений нет'}</h3>
      {changes.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead><tr><th>Состав</th><th>Что</th><th>Изменение</th><th>Было</th><th>Стало</th></tr></thead>
            <tbody>
              {changes.map((c, i) => (
                <tr key={i}>
                  <td>{trainName(c.trainId)}</td>
                  <td>{c.label}</td>
                  <td><span className={`badge ${c.kind === 'REMOVED' ? 'CRITICAL' : 'WARNING'}`}>{kind[c.kind]}</span></td>
                  <td>{c.before ? `${time(c.before.startAt)} – ${clock(c.before.endAt)}` : '—'}</td>
                  <td>{c.after ? `${time(c.after.startAt)} – ${clock(c.after.endAt)}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {changes.length > 0 && (
        <>
          <label className="check"><input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} />
            Только составы с изменениями</label>
          <PlanningCalendar data={after.data} title="Предложенный план" details={false}
            trainIds={onlyChanged ? changedTrains : null}
            highlight={new Set(changes.flatMap((c) => (c.after ? [c.after.id] : [])))} />
        </>
      )}
    </>
  )
}

export function InboxSection({ calendar, activePlanId }: { calendar?: CalendarData; activePlanId: string | null }) {
  const [selected, setSelected] = useState<ChangeRequest | null>(null)
  return (
    <section className="card">
      <h2 className="pad-h"><Icon name="bell" />Входящие заявки</h2>
      <div className="pad-x"><MockNotice /></div>
      <RequestList scenarioId={calendar?.scenarioId} calendar={calendar} selectedId={selected?.id} onSelect={setSelected} />
      {selected && (
        <div className="pad">
          <h3>Заявка № {selected.number}</h3>
          <p>{summary(selected, calendar)}{selected.comment && <span className="muted"> · {selected.comment}</span>}</p>
          <RequestChain r={selected} />
          {selected.planId && activePlanId && selected.planId !== activePlanId
            ? <PlanCompare beforeId={activePlanId} afterId={selected.planId} />
            : <p className="muted">Сравнение появится, когда по заявке будет рассчитан новый план и будет действующий план для сравнения.</p>}
        </div>
      )}
    </section>
  )
}
