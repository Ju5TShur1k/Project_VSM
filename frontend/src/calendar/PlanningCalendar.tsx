import { useState } from 'react'
import './planning-calendar.css'

export type CalendarEvent = {
  id: string
  kind: 'TRIP' | 'SERVICE'
  trainId: string
  resourceId?: string
  label: string
  startAt: string
  endAt: string
  source: string
  reason: string
  cycleCode?: string
  covers?: string[]
  releaseOdometerKm?: number
  dueOdometerKm?: number
  dueAt?: string
}

export type CalendarData = {
  scenarioId: string
  snapshotHash: string
  provenance: string
  policy: string
  solverStatus: string
  independentlyValidated: boolean
  horizonStart: string
  horizonEnd: string
  trains: { id: string; label: string }[]
  resources: { id: string; label: string }[]
  events: CalendarEvent[]
}

const moscow = (iso: string) => new Date(iso).toLocaleString('ru-RU', {
  timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'
})

export default function PlanningCalendar({ data }: { data: CalendarData }) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const start = Date.parse(data.horizonStart)
  const end = Date.parse(data.horizonEnd)
  const duration = end - start
  const validHorizon = Number.isFinite(duration) && duration > 0
  const events = data.events.filter((event) => {
    const s = Date.parse(event.startAt)
    const e = Date.parse(event.endAt)
    return Number.isFinite(s) && Number.isFinite(e) && s >= start && e <= end && e > s
  })
  const selected = events.find((event) => event.id === selectedId)
  const hourCount = duration / 3_600_000
  const tickHours = hourCount <= 12 ? 1 : hourCount <= 48 ? 4 : 24
  const tickCount = Math.min(31, Math.ceil(hourCount / tickHours) + 1)

  if (!validHorizon) return <p className="error">Некорректный горизонт календаря.</p>

  const rows = [
    ...data.trains.map((train) => ({ key: `train:${train.id}`, label: train.label,
      events: events.filter((event) => event.trainId === train.id) })),
    ...data.resources.map((resource) => ({ key: `resource:${resource.id}`, label: resource.label,
      events: events.filter((event) => event.resourceId === resource.id) }))
  ]

  return (
    <section className="card pad planning-calendar" aria-label="Календарь по поездам и ресурсам">
      <div className="bar">
        <h2>Календарь работ и рейсов</h2>
        <span className="badge DRAFT">{data.provenance}</span>
      </div>
      <p className="muted">Сценарий <code>{data.scenarioId}</code> · snapshot <code>{data.snapshotHash}</code> · {data.policy} · {data.solverStatus}</p>
      {!data.independentlyValidated && <p className="error">Независимая проверка D2 ещё не выполнена. Календарь нельзя утверждать.</p>}
      <div className="calendar-scroll">
        <div className="calendar-grid" style={{ width: Math.max(900, Math.min(8000, hourCount * 40)) }}>
          <div className="calendar-axis">
            {Array.from({ length: tickCount }, (_, i) => {
              const time = Math.min(end, start + i * tickHours * 3_600_000)
              return <span key={i} style={{ left: `${((time - start) / duration) * 100}%` }}>{moscow(new Date(time).toISOString())}</span>
            })}
          </div>
          {rows.map((row) => <div className="calendar-row" key={row.key}>
            <strong>{row.label}</strong>
            <div className="calendar-track" style={{ backgroundSize: `${(tickHours / hourCount) * 100}% 100%` }}>
              {row.events.map((event) => <button
                key={`${row.key}:${event.id}`}
                type="button"
                className={`calendar-block ${event.kind.toLowerCase()} ${selectedId === event.id ? 'selected' : ''}`}
                style={{ left: `${((Date.parse(event.startAt) - start) / duration) * 100}%`,
                  width: `${((Date.parse(event.endAt) - Date.parse(event.startAt)) / duration) * 100}%` }}
                aria-label={`${event.label}, ${moscow(event.startAt)}–${moscow(event.endAt)}`}
                aria-pressed={selectedId === event.id}
                onClick={() => setSelectedId(event.id)}
              >{event.label}</button>)}
            </div>
          </div>)}
        </div>
      </div>
      <p className="muted">Интервалы [начало, конец); касание границ разрешено. Время показано по Москве.</p>
      {selected ? <div className="calendar-detail" aria-live="polite">
        <h3>{selected.label}</h3>
        <dl className="kv">
          <dt>Интервал</dt><dd>{moscow(selected.startAt)} — {moscow(selected.endAt)}</dd>
          <dt>Поезд</dt><dd>{data.trains.find((train) => train.id === selected.trainId)?.label ?? selected.trainId}</dd>
          {selected.resourceId && <><dt>Ресурс</dt><dd>{data.resources.find((resource) => resource.id === selected.resourceId)?.label ?? selected.resourceId}</dd></>}
          {selected.cycleCode && <><dt>Цикл</dt><dd>{selected.cycleCode}</dd></>}
          {Boolean(selected.covers?.length) && <><dt>Покрывает</dt><dd>{selected.covers?.join(', ')}</dd></>}
          {selected.releaseOdometerKm !== undefined && <><dt>Пробег от</dt><dd>{selected.releaseOdometerKm.toLocaleString('ru-RU')} км</dd></>}
          {selected.dueOdometerKm !== undefined && <><dt>Пробег до</dt><dd>{selected.dueOdometerKm.toLocaleString('ru-RU')} км</dd></>}
          {selected.dueAt && <><dt>Срок в сценарии</dt><dd>{moscow(selected.dueAt)}</dd></>}
          <dt>Источник</dt><dd>{selected.source}</dd>
          <dt>Причина окна</dt><dd>{selected.reason}</dd>
        </dl>
      </div> : <p className="muted">Выберите рейс или работу, чтобы увидеть источник и причину размещения.</p>}
    </section>
  )
}
