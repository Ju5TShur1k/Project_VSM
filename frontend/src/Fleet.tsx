import { useMemo, useState } from 'react'
import { ru } from './api'
import type { CalendarData, CalendarEvent } from './calendar/PlanningCalendar'
import PlanningCalendar from './calendar/PlanningCalendar'
import { Icon, Info } from './Icons'
import { clock, time } from './format'

const DAY = 86_400_000
const ROW_LIMIT = 200
const day = (ms: number) => new Date(ms).toLocaleDateString('ru-RU', { timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', weekday: 'short' })

// ponytail: distance parsed from the trip label ("Рейс R1 · 670 км"); switch to
// CalendarEvent.distanceKm once the API adds it (docs/UI_CONTRACT.md).
const km = (label: string) => Number(label.match(/(\d+)\s*км/)?.[1] ?? 0)

type FleetTrain = { id: string; label: string }
// Maintenance and cleaning are both SERVICE blocks; cleanings are labelled "Уборка · N мин".
const isCleaning = (e: CalendarEvent) => e.kind === 'SERVICE' && e.label.startsWith('Уборка')
const isWork = (e: CalendarEvent) => e.kind === 'SERVICE' && !isCleaning(e)

export default function Fleet({ calendar: cal, loading, detailed }: { calendar?: CalendarData; loading: boolean; detailed: boolean }) {
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<'ALL' | 'TRIP' | 'SERVICE'>('ALL')
  const [scale, setScale] = useState<'DAY' | 'WEEK' | 'ALL'>('DAY')
  const [dayIndex, setDayIndex] = useState(0)
  const [cardId, setCardId] = useState<string | null>(null)

  const fleet: FleetTrain[] = useMemo(() => (cal?.trains ?? [])
    .map((l) => ({ id: l.id, label: l.label }))
    .sort((a, b) => a.label.localeCompare(b.label, 'ru', { numeric: true })), [cal])

  const q = query.trim().toLowerCase()
  const matches = (e: CalendarEvent) => !q || e.label.toLowerCase().includes(q) || (fleet.find((t) => t.id === e.trainId)?.label.toLowerCase().includes(q) ?? false)
  const shownTrains = fleet.filter((t) => !q || t.label.toLowerCase().includes(q) || (cal?.events.some((e) => e.trainId === t.id && e.label.toLowerCase().includes(q)) ?? false))

  const start = cal ? Date.parse(cal.horizonStart) : 0
  const end = cal ? Date.parse(cal.horizonEnd) : 0
  const days = cal ? Array.from({ length: Math.ceil((end - start) / DAY) }, (_, i) => start + i * DAY) : []
  const from = scale === 'ALL' ? start : start + dayIndex * DAY
  const to = scale === 'ALL' ? end : Math.min(end, from + (scale === 'DAY' ? DAY : 7 * DAY))

  const events = (cal?.events ?? [])
    .filter((e) => (kind === 'ALL' || e.kind === kind) && matches(e) && Date.parse(e.startAt) < to && Date.parse(e.endAt) > from)
    .sort((a, b) => a.startAt.localeCompare(b.startAt))
  const next = (id: string) => cal?.events.filter((e) => isWork(e) && e.trainId === id).sort((a, b) => a.startAt.localeCompare(b.startAt))[0]
  const card = fleet.find((t) => t.id === cardId)
  const trainName = (id: string) => fleet.find((t) => t.id === id)?.label ?? id

  return (
    <>
      <section className="card pad">
        <h2><Icon name="train" />Парк<Info text="Все составы, рейсы, ТО и уборки последнего построенного плана. Поиск сужает таблицы и календарь; клик по составу открывает его карточку." /></h2>
        {!cal && <p className="muted">{loading ? 'Загрузка плана…' : 'План ещё не построен.'}</p>}
        <div className="row filters">
          <label className="grow">Поиск
            <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Состав или рейс, например CASE-07 или D4-R2" />
          </label>
          {cal && <>
            <label>Показать
              <select value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
                <option value="ALL">Рейсы и ТО</option><option value="TRIP">Только рейсы</option><option value="SERVICE">ТО и уборки</option>
              </select>
            </label>
            <label>Масштаб
              <select value={scale} onChange={(e) => setScale(e.target.value as typeof scale)}>
                <option value="DAY">Сутки</option><option value="WEEK">Неделя</option><option value="ALL">Весь горизонт</option>
              </select>
            </label>
            {scale !== 'ALL' && <label>С
              <select value={dayIndex} onChange={(e) => setDayIndex(Number(e.target.value))}>
                {days.map((d, i) => <option key={d} value={i}>{day(d)}</option>)}
              </select>
            </label>}
          </>}
        </div>
      </section>

      {fleet.length > 0 && (
        <section className="card">
          <h2 className="pad-h"><Icon name="train" />Составы · {shownTrains.length} из {fleet.length}</h2>
          <div className="table-scroll">
            <table>
              <thead><tr><th>Состав</th><th>Ближайшее ТО</th><th className="num">ТО</th><th className="num">Уборок</th><th className="num">Рейсов</th></tr></thead>
              <tbody>
                {shownTrains.map((t) => {
                  const n = next(t.id)
                  const own = cal?.events.filter((e) => e.trainId === t.id) ?? []
                  return (
                    <tr key={t.id} className={`clickable ${cardId === t.id ? 'current' : ''}`} onClick={() => setCardId(t.id)}>
                      <td><button className="link" onClick={() => setCardId(t.id)}>{t.label}</button></td>
                      <td>{n ? `${n.cycleCode ?? n.label.split(' · ')[0]} · ${time(n.startAt)}` : 'в горизонте не требуется'}</td>
                      <td className="num">{own.filter(isWork).length}</td>
                      <td className="num">{own.filter(isCleaning).length}</td>
                      <td className="num">{own.filter((e) => e.kind === 'TRIP').length}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {card && <TrainCard train={card} calendar={cal} detailed={detailed} onClose={() => setCardId(null)} />}

      {cal && (
        <>
          <section className="card">
            <h2 className="pad-h"><Icon name="route" />Рейсы и ТО · {events.length}</h2>
            <div className="table-scroll">
              <table>
                <thead><tr><th>Начало</th><th>Конец</th><th>Состав</th><th>Что</th>{detailed && <th>Путь</th>}</tr></thead>
                <tbody>
                  {events.slice(0, ROW_LIMIT).map((e) => (
                    <tr key={`${e.kind}:${e.id}`} className="clickable" onClick={() => setCardId(e.trainId)}>
                      <td>{time(e.startAt)}</td><td>{clock(e.endAt)}</td><td>{trainName(e.trainId)}</td>
                      <td><span className={`badge ${e.kind === 'SERVICE' ? 'WARNING' : 'DRAFT'}`}>{e.kind === 'SERVICE' ? 'ТО' : 'Рейс'}</span> {e.label.replace(/^Рейс /, '')}</td>
                      {detailed && <td>{e.resourceId ?? ''}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {events.length > ROW_LIMIT && <p className="muted empty">Показаны первые {ROW_LIMIT}. Уточните поиск, день или тип.</p>}
            {events.length === 0 && <p className="muted empty">Ничего не найдено.</p>}
          </section>
          <PlanningCalendar data={cal} details={detailed} range={{ start: from, end: to }}
            trainIds={q ? new Set(shownTrains.map((t) => t.id)) : null} />
        </>
      )}
    </>
  )
}

// Everything here comes from the plan's calendar, i.e. the same snapshot the solver used.
function TrainCard({ train, calendar, detailed, onClose }: { train: FleetTrain; calendar?: CalendarData; detailed: boolean; onClose: () => void }) {
  const events = calendar?.events.filter((e) => e.trainId === train.id) ?? []
  const trips = events.filter((e) => e.kind === 'TRIP').sort((a, b) => a.startAt.localeCompare(b.startAt))
  const services = events.filter((e) => e.kind === 'SERVICE').sort((a, b) => a.startAt.localeCompare(b.startAt))
  const works = services.filter(isWork)
  const next = works[0]
  const tripsBefore = next ? trips.filter((t) => Date.parse(t.endAt) <= Date.parse(next.startAt)) : trips
  const n = (v: number) => v.toLocaleString('ru-RU')
  return (
    <section className="card pad">
      <div className="bar">
        <h2><Icon name="train" />Состав {train.label}</h2>
        <button className="btn-outline" onClick={onClose}>Закрыть</button>
      </div>
      <dl className="kv">
        <dt>Рейсов в горизонте</dt>
        <dd>{calendar ? `${trips.length}${detailed ? ` · ${n(trips.reduce((s, t) => s + km(t.label), 0))} км` : ''}` : 'появится после расчёта'}</dd>
        {next && <>
          <dt>Ближайшее ТО</dt><dd>{next.cycleCode ?? next.label} · {time(next.startAt)} – {time(next.endAt)}</dd>
          {detailed && next.releaseOdometerKm != null && next.dueOdometerKm != null &&
            <><dt>Окно по пробегу</dt><dd>{n(next.releaseOdometerKm)} – {n(next.dueOdometerKm)} км</dd></>}
          <dt>Рейсов до ТО</dt><dd>{tripsBefore.length}</dd>
          {works.length > 1 && <><dt>Всего ТО</dt><dd>{works.map((w) => w.cycleCode ?? w.label).join(', ')}</dd></>}
        </>}
        <dt>Уборок</dt><dd>{services.length - works.length}</dd>
        {calendar && !next && <><dt>ТО в горизонте</dt><dd>не требуется</dd></>}
      </dl>
      {trips.length > 0 && (
        <details>
          <summary>Рейсы и ТО состава ({trips.length + services.length})</summary>
          <ul className="viol">
            {[...trips, ...services].sort((a, b) => a.startAt.localeCompare(b.startAt)).map((e) =>
              <li key={e.id}>{time(e.startAt)}–{clock(e.endAt)} · {e.kind === 'SERVICE' ? 'ТО ' : ''}{e.label}</li>)}
          </ul>
        </details>
      )}
    </section>
  )
}
