import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { CalendarData } from './calendar/PlanningCalendar'
import { Icon } from './Icons'
import { MockNotice, PlanBadge, RequestChain, RequestList, summary, time, usePlanView } from './Requests'
import { ChangeRequest, RequestPayload, requestsApi, UrgentMaintenance } from './changeRequests'

// <input type="datetime-local"> works in local wall time; the model runs in Moscow time.
const toInput = (iso: string) => new Date(iso).toLocaleString('sv-SE', { timeZone: 'Europe/Moscow' }).replace(' ', 'T').slice(0, 16)
const toIso = (local: string) => `${local}:00+03:00`
const shift = (local: string, min: number) => toInput(new Date(Date.parse(toIso(local)) + min * 60_000).toISOString())
const byLabel = (cal: CalendarData) => [...cal.trains].sort((a, b) => a.label.localeCompare(b.label, 'ru', { numeric: true }))
const newId = () => crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`

const REASONS = ['Задержка отправления', 'Задержка прибытия', 'Изменение графика', 'Иное']
const SOURCES = ['Звонок с линии', 'Диспетчерский центр', 'Поездная бригада', 'АСУ']
const PROBLEMS = ['Замечание машиниста', 'Бортовая диагностика', 'Осмотр при приёмке', 'Повреждение в пути', 'Иное']
const WORKS = ['Внеплановый осмотр', 'Ремонт оборудования', 'IS100', 'IS200']
const URGENCY: Record<UrgentMaintenance['urgency'], string> = {
  IMMEDIATE: 'Немедленно, выпуск запрещён', WITHIN_24H: 'В течение суток', WITHIN_HORIZON: 'В пределах горизонта плана'
}

export default function Events({ username, ownPlanId, approvedHere }: { username: string; ownPlanId?: string; approvedHere?: string }) {
  const view = usePlanView(ownPlanId, approvedHere)
  const cal = view.calendar
  const [form, setForm] = useState<'trip' | 'urgent'>('trip')
  const [last, setLast] = useState<ChangeRequest | null>(null)

  return (
    <>
      <section className="card pad">
        <h2><Icon name="alert" />Сообщить о событии</h2>
        {!cal ? (
          <p className="muted">Плана пока нет: планировщик должен загрузить данные и рассчитать план.</p>
        ) : (
          <>
            <p className="muted">
              План: <PlanBadge status={view.plan?.status} isActive={view.planId === view.activePlanId} /> · версия данных{' '}
              <code title={cal.snapshotHash}>{cal.snapshotHash.slice(0, 12)}</code>
            </p>
            <div className="seg" role="tablist">
              <button role="tab" aria-selected={form === 'trip'} className={form === 'trip' ? 'on' : ''} onClick={() => setForm('trip')}>Изменился рейс</button>
              <button role="tab" aria-selected={form === 'urgent'} className={form === 'urgent' ? 'on' : ''} onClick={() => setForm('urgent')}>Нужно срочное ТО</button>
            </div>
            {form === 'trip' ? <TripForm cal={cal} user={username} onSent={setLast} /> : <UrgentForm cal={cal} user={username} onSent={setLast} />}
            <MockNotice />
            {last && (
              <div className="sent">
                <p><strong>Заявка № {last.number} отправлена.</strong> {summary(last, cal)}</p>
                <RequestChain r={last} />
              </div>
            )}
          </>
        )}
      </section>
      <section className="card">
        <h2 className="pad-h"><Icon name="bell" />Мои и общие заявки</h2>
        <RequestList scenarioId={cal?.scenarioId} calendar={cal} />
      </section>
    </>
  )
}

function useSend(cal: CalendarData, user: string, onSent: (r: ChangeRequest) => void) {
  const qc = useQueryClient()
  // One id per filled-in form: a double click or retry resends the same request.
  const [clientRequestId, setClientRequestId] = useState(newId)
  return useMutation({
    mutationFn: (v: { payload: RequestPayload; comment: string }) =>
      requestsApi.create(cal.scenarioId, { clientRequestId, expectedSnapshotHash: cal.snapshotHash, ...v }, user),
    onSuccess: (r) => {
      onSent(r)
      setClientRequestId(newId())
      qc.invalidateQueries({ queryKey: ['requests'] })
    }
  })
}

function TripForm({ cal, user, onSent }: { cal: CalendarData; user: string; onSent: (r: ChangeRequest) => void }) {
  const [trainId, setTrainId] = useState(byLabel(cal)[0]?.id ?? '')
  const trips = cal.events.filter((e) => e.kind === 'TRIP' && e.trainId === trainId).sort((a, b) => a.startAt.localeCompare(b.startAt))
  const [tripId, setTripId] = useState(trips[0]?.id ?? '')
  const trip = trips.find((t) => t.id === tripId)
  const [dep, setDep] = useState(trip ? toInput(trip.startAt) : '')
  const [arr, setArr] = useState(trip ? toInput(trip.endAt) : '')
  const [reason, setReason] = useState(REASONS[0])
  const [source, setSource] = useState(SOURCES[0])
  const [comment, setComment] = useState('')
  const send = useSend(cal, user, onSent)

  const pickTrip = (id: string) => {
    const t = cal.events.find((e) => e.id === id)
    setTripId(id)
    if (t) { setDep(toInput(t.startAt)); setArr(toInput(t.endAt)) }
  }
  const pickTrain = (id: string) => {
    setTrainId(id)
    const first = cal.events.filter((e) => e.kind === 'TRIP' && e.trainId === id).sort((a, b) => a.startAt.localeCompare(b.startAt))[0]
    pickTrip(first?.id ?? '')
  }
  const invalid = !trip ? 'Выберите рейс' : Date.parse(toIso(arr)) <= Date.parse(toIso(dep)) ? 'Прибытие должно быть позже отправления'
    : trip && toIso(dep) === toIso(toInput(trip.startAt)) && toIso(arr) === toIso(toInput(trip.endAt)) ? 'Время не изменилось' : null

  return (
    <form className="event-form" onSubmit={(e) => {
      e.preventDefault()
      if (invalid || !trip) return
      if (!confirm(`Отправить заявку?\n${trip.label}\nбыло ${time(trip.startAt)}–${time(trip.endAt)}\nстанет ${dep.slice(11)}–${arr.slice(11)}`)) return
      send.mutate({ payload: { kind: 'TRIP_CHANGE', trainId, tripId, newDepartureAt: toIso(dep), newArrivalAt: toIso(arr), reason, source }, comment })
    }}>
      <div className="row">
        <label>Состав
          <select value={trainId} onChange={(e) => pickTrain(e.target.value)}>
            {byLabel(cal).map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
        </label>
        <label className="grow">Рейс
          <select value={tripId} onChange={(e) => pickTrip(e.target.value)}>
            {trips.map((t) => <option key={t.id} value={t.id}>{time(t.startAt)}–{time(t.endAt).slice(-5)} · {t.label}</option>)}
          </select>
        </label>
      </div>
      <div className="row">
        <label>Новое отправление (МСК)<input type="datetime-local" value={dep} onChange={(e) => setDep(e.target.value)} required /></label>
        <label>Новое прибытие (МСК)<input type="datetime-local" value={arr} onChange={(e) => setArr(e.target.value)} required /></label>
        <button type="button" className="btn-outline" onClick={() => { setDep(shift(dep, 5)); setArr(shift(arr, 5)) }}>Сдвинуть на +5 мин</button>
      </div>
      <div className="row">
        <label>Причина<select value={reason} onChange={(e) => setReason(e.target.value)}>{REASONS.map((r) => <option key={r}>{r}</option>)}</select></label>
        <label>Источник<select value={source} onChange={(e) => setSource(e.target.value)}>{SOURCES.map((r) => <option key={r}>{r}</option>)}</select></label>
        <label className="grow">Пояснение<input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder="Необязательно" /></label>
      </div>
      {invalid && trip && <p className="muted">{invalid}</p>}
      {send.isError && <p className="error">{send.error.message}</p>}
      <button disabled={!!invalid || send.isPending}>{send.isPending ? 'Отправка…' : 'Отправить заявку'}</button>
    </form>
  )
}

function UrgentForm({ cal, user, onSent }: { cal: CalendarData; user: string; onSent: (r: ChangeRequest) => void }) {
  const [trainId, setTrainId] = useState(byLabel(cal)[0]?.id ?? '')
  const [problem, setProblem] = useState(PROBLEMS[0])
  const [detectedAt, setDetectedAt] = useState(toInput(cal.horizonStart))
  const [notBeforeTripEnd, setNotBeforeTripEnd] = useState(true)
  const [urgency, setUrgency] = useState<UrgentMaintenance['urgency']>('WITHIN_24H')
  const [dueBy, setDueBy] = useState('')
  const [workType, setWorkType] = useState(WORKS[0])
  const [comment, setComment] = useState('')
  const send = useSend(cal, user, onSent)
  const train = cal.trains.find((t) => t.id === trainId)

  return (
    <form className="event-form" onSubmit={(e) => {
      e.preventDefault()
      if (!confirm(`Отправить заявку на срочное ТО?\n${train?.label}: ${problem}\n${URGENCY[urgency]}`)) return
      send.mutate({ payload: { kind: 'URGENT_MAINTENANCE', trainId, problem, detectedAt: toIso(detectedAt), notBeforeTripEnd, urgency,
        dueBy: dueBy ? toIso(dueBy) : null, workType }, comment })
    }}>
      <div className="row">
        <label>Состав
          <select value={trainId} onChange={(e) => setTrainId(e.target.value)}>
            {byLabel(cal).map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
        </label>
        <label>Что обнаружено<select value={problem} onChange={(e) => setProblem(e.target.value)}>{PROBLEMS.map((r) => <option key={r}>{r}</option>)}</select></label>
        <label>Время обнаружения (МСК)<input type="datetime-local" value={detectedAt} onChange={(e) => setDetectedAt(e.target.value)} required /></label>
      </div>
      <div className="row">
        <label>Срочность
          <select value={urgency} onChange={(e) => setUrgency(e.target.value as UrgentMaintenance['urgency'])}>
            {Object.entries(URGENCY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>Крайний срок (МСК)<input type="datetime-local" value={dueBy} onChange={(e) => setDueBy(e.target.value)} /></label>
        <label>Вид работы<select value={workType} onChange={(e) => setWorkType(e.target.value)}>{WORKS.map((r) => <option key={r}>{r}</option>)}</select></label>
      </div>
      <label className="check"><input type="checkbox" checked={notBeforeTripEnd} onChange={(e) => setNotBeforeTripEnd(e.target.checked)} />
        Не раньше окончания текущего рейса</label>
      <label>Пояснение<input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder="Например: стук в тележке второго вагона" /></label>
      <p className="muted">Диспетчер сообщает потребность. Конкретное окно для работы выбирает планировщик при пересчёте.</p>
      {send.isError && <p className="error">{send.error.message}</p>}
      <button disabled={send.isPending}>{send.isPending ? 'Отправка…' : 'Отправить заявку'}</button>
    </form>
  )
}
