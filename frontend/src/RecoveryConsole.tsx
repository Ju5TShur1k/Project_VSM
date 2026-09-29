import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, Conflict, RecoveryBoard } from './api'

const city = (value: string) => value.replace(/SPB_DEPOT/g, 'Санкт-Петербург').replace(/MOSCOW/g, 'Москва')
const time = (value: string) => new Date(value).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
const inputTime = (value: string) => new Date(value).toLocaleString('sv-SE', {timeZone:'Europe/Moscow'}).replace(' ', 'T').slice(0,16)
const timestamp = (value: string) => `${value}:00+03:00`
const status: Record<string,string> = {LINE:'На линии', RESERVE:'Горячий резерв', MAINTENANCE:'Плановое ТО', FAILED:'Отказ · выпуск запрещён', SCHEDULED:'По графику', REPLACED:'Замена согласована', UNCOVERED:'Нет состава', NEEDS_REPLACEMENT:'Нужна замена', REPLACEMENT_ASSIGNED:'Замена согласована · ремонт не принят', RELEASED_TO_RESERVE:'Ремонт принят · состав в резерве'}

export default function RecoveryConsole({board, onChange}: {board: RecoveryBoard; onChange: (value: RecoveryBoard) => void}) {
  const remaining = board.trips.filter(t => t.coverage !== 'UNCOVERED' && new Date(t.departureAt).getTime() > new Date(board.asOf).getTime())
    .sort((a,b)=>new Date(a.departureAt).getTime()-new Date(b.departureAt).getTime() || a.effectiveTrain.localeCompare(b.effectiveTrain))
  const first = remaining[0]
  const [tripId,setTripId] = useState(first?.id ?? '')
  const initialFailureTime=Math.max(new Date(board.asOf).getTime(),first ? new Date(first.departureAt).getTime()-60*60*1000 : new Date(board.asOf).getTime())
  const [occurredAt,setOccurredAt] = useState(inputTime(new Date(initialFailureTime).toISOString()))
  const [expectedRepairAt,setExpectedRepairAt] = useState(inputTime(new Date(initialFailureTime+4*60*60*1000).toISOString()))
  const [description,setDescription] = useState('Отказ тягового оборудования: выпуск состава запрещён')
  const [clock,setClock] = useState(inputTime(new Date(new Date(board.windowStart).getTime()+10*60*60*1000).toISOString()))
  const [completedAt,setCompletedAt] = useState(inputTime(new Date(new Date(board.windowStart).getTime()+9.5*60*60*1000).toISOString()))
  const [acceptedAt,setAcceptedAt] = useState(inputTime(new Date(new Date(board.windowStart).getTime()+10*60*60*1000).toISOString()))
  const [reference,setReference] = useState('Модельная приёмка для демонстрации')
  const action = useMutation({
    mutationFn: ({command,body}: {command: 'failures' | 'replacements' | 'releases' | 'clock'; body: Record<string,unknown>}) => api.recoveryCommand(board,command,body),
    onSuccess:onChange,
    onError:async (error) => { if(error instanceof Conflict) onChange(await api.getRecovery(board.id)) }
  })
  useEffect(() => {
    if(!remaining.some(t=>t.id===tripId)) {
      const next=remaining[0]
      setTripId(next?.id ?? '')
      if(next) {
        const at=Math.max(new Date(board.asOf).getTime(),new Date(next.departureAt).getTime()-60*60*1000)
        setOccurredAt(inputTime(new Date(at).toISOString()))
        if(expectedRepairAt && new Date(timestamp(expectedRepairAt)).getTime()<=at) setExpectedRepairAt(inputTime(new Date(at+4*60*60*1000).toISOString()))
      }
    }
  },[board.version,tripId])
  return <section className="card pad recovery">
    <h2>Оперативный парк · отказ перед рейсом и замена</h2>
    <p>Модельный день {time(board.windowStart)}–{time(board.windowEnd)}. Состояние на <strong>{time(board.asOf)}</strong>.</p>
    <div className="recovery-summary">
      <div><strong>{board.trainCount}</strong><span>составов в парке</span></div>
      <div><strong>{board.availableTrainCount} / {board.trainCount}</strong><span>доступны в текущий момент модели</span></div>
      <div><strong>{board.uncoveredTripCount}</strong><span>рейсов без замены</span></div>
      <div><strong>{board.incompletePairCount}</strong><span>неполных парных отправок</span></div>
    </div>
    <div className="row reserve-cards">{board.reserve.map(r => <div className={`reserve-card ${r.deficit ? 'shortage' : ''}`} key={r.location}>
      <strong>{city(r.location)}: {r.available} / {r.target}</strong>
      <span>{r.deficit ? `Дефицит резерва: ${r.deficit}` : 'Целевой резерв обеспечен'}</span>
    </div>)}</div>
    <p className="muted">Резерв можно вывести на линию. Его место занимает отремонтированный состав после отдельной приёмки. Цель 2+2 — сигнал о запасе, а не запрет замены при отказе.</p>
    {board.messages.filter(m => m.startsWith('ДЕФИЦИТ') || m.startsWith('НЕТ') || m.startsWith('НЕПОКРЫТЫЕ')).map(m => <p role="alert" className="error" key={m}>{city(m)}</p>)}

    <h3>1. Зарегистрировать модельный отказ до отправления</h3>
    <form className="recovery-form" onSubmit={e => {e.preventDefault(); action.mutate({command:'failures',body:{tripId,occurredAt:timestamp(occurredAt),expectedRepairAt:expectedRepairAt?timestamp(expectedRepairAt):null,description}})}}>
      <label>Рейс и текущий состав<select value={tripId} onChange={e => {
        setTripId(e.target.value)
        const t=board.trips.find(t=>t.id===e.target.value)
        if(t) {
          const at=Math.max(new Date(board.asOf).getTime(),new Date(t.departureAt).getTime()-60*60*1000)
          setOccurredAt(inputTime(new Date(at).toISOString()))
          if(expectedRepairAt && new Date(timestamp(expectedRepairAt)).getTime()<=at) setExpectedRepairAt(inputTime(new Date(at+4*60*60*1000).toISOString()))
        }
      }}>
        {remaining.map(t => <option key={t.id} value={t.id}>{t.effectiveTrain} · {time(t.departureAt)} · {city(t.origin)} → {city(t.destination)} · {t.label}</option>)}
      </select></label>
      <div className="row"><label>Время отказа, Москва<input type="datetime-local" value={occurredAt} onChange={e=>setOccurredAt(e.target.value)} required /></label>
        <label>Прогноз окончания ремонта, Москва<input type="datetime-local" value={expectedRepairAt} onChange={e=>setExpectedRepairAt(e.target.value)} /></label></div>
      <label>Причина запрета выпуска<input value={description} onChange={e=>setDescription(e.target.value)} required maxLength={1000} /></label>
      <button disabled={action.isPending || !remaining.some(t=>t.id===tripId)}>Зарегистрировать отказ</button>
    </form>
    {action.isError && <p role="alert" className="error">{city(action.error.message)}</p>}

    {board.faults.map(f => <article className="fault-card" key={f.id}>
      <h3>{f.train} · {status[f.status] ?? f.status}</h3>
      <p>{f.description}. Отказ {time(f.occurredAt)}; затронуто рейсов текущих суток: {f.affectedTripIds.length}.</p>
      <p className="muted">Прогноз ремонта: {f.expectedRepairAt ? time(f.expectedRepairAt) : 'не указан'}. Он не подтверждает готовность к выпуску.</p>
      {f.replacementTrain && <p><strong>{f.replacementTrain}</strong> принимает оставшийся оборот текущих суток. Время, маршрут и второй состав пары сохраняются.</p>}
      {f.candidates.length>0 && <>
        <h3>2. Проверить и согласовать замену</h3>
        <div className="table-scroll"><table><thead><tr><th>Кандидат / город</th><th>Обоснование</th><th>Решение</th></tr></thead><tbody>{f.candidates.map(c=><tr key={c.trainId}>
          <td><strong>{c.externalId}</strong><br/>{city(c.location)}</td><td>{city(c.reasons.join('; '))}</td>
          <td>{c.eligible?<button disabled={action.isPending} onClick={()=>action.mutate({command:'replacements',body:{faultId:f.id,trainId:c.trainId}})}>Согласовать {c.externalId}</button>:<span className="error">Недопустим</span>}</td>
        </tr>)}</tbody></table></div>
        {!f.candidates.some(c=>c.eligible) && <p className="error"><strong>Нет допустимой замены.</strong> Рейс нельзя считать покрытым, а неисправный поезд — выпущенным.</p>}
      </>}
      {!f.acceptedAt && <details><summary>3. Зафиксировать ремонт и приёмку · вернуть {f.train} в резерв</summary>
        <p className="muted">В демонстрации вводятся модельные факты завершения ремонта и приёмки. В эксплуатации нужен подтверждённый документ допуска; приёмка не планирует ремонт автоматически.</p>
        <form className="recovery-form" onSubmit={e=>{e.preventDefault(); const train=board.trains.find(t=>t.id===f.trainId); action.mutate({command:'releases',body:{faultId:f.id,repairCompletedAt:timestamp(completedAt),acceptedAt:timestamp(acceptedAt),location:train?.location,acceptanceReference:reference}})}}>
          <div className="row"><label>Ремонт завершён, Москва<input type="datetime-local" required value={completedAt} onChange={e=>setCompletedAt(e.target.value)} /></label>
            <label>Приёмка, Москва<input type="datetime-local" required value={acceptedAt} onChange={e=>setAcceptedAt(e.target.value)} /></label></div>
          <label>Документ приёмки<input value={reference} required maxLength={200} onChange={e=>setReference(e.target.value)} /></label>
          <button disabled={action.isPending}>Подтвердить модельную приёмку {f.train}</button>
        </form>
      </details>}
      {f.acceptedAt && <p>Приёмка зафиксирована {time(f.acceptedAt)}. Отремонтированный состав передан в резерв; замена остаётся на линии.</p>}
    </article>)}
    <details><summary>Проверить, что прогноз ремонта сам по себе не восстанавливает резерв</summary>
      <form className="row" onSubmit={e=>{e.preventDefault();action.mutate({command:'clock',body:{asOf:timestamp(clock)}})}}>
        <label>Модельное время, Москва<input type="datetime-local" required value={clock} onChange={e=>setClock(e.target.value)} /></label>
        <button disabled={action.isPending}>Продвинуть время без приёмки</button>
      </form>
    </details>
    <details><summary>График текущих суток · {board.trips.length} рейсов · назначения и пары</summary>
      <div className="table-scroll"><table><thead><tr><th>Отправление / прибытие</th><th>План → фактическое предложение</th><th>Маршрут</th><th>Покрытие</th></tr></thead><tbody>{board.trips.map(t=><tr key={t.id}>
        <td>{time(t.departureAt)}–{time(t.arrivalAt)}<br/><small>{t.label}</small></td><td>{t.plannedTrain} → {t.effectiveTrain}</td>
        <td>{city(t.origin)} → {city(t.destination)}</td><td className={t.coverage==='UNCOVERED'?'error':''}>{status[t.coverage]}</td>
      </tr>)}</tbody></table></div>
    </details>
    <p className="muted">Уборка обязательна после четвёртого рейса; неизвестный счётчик и отсутствие принятой уборки исключают кандидатуру. Подготовка {board.policy.preparationMinutes} мин — явное модельное допущение. Фактические времена и допуск должны быть подтверждены заказчиком.</p>
    {board.messages.filter(m=>!m.startsWith('ДЕФИЦИТ')&&!m.startsWith('НЕТ')&&!m.startsWith('НЕПОКРЫТЫЕ')).map(m=><p className="muted" key={m}>{m}</p>)}
    <details><summary>Источник и история решений</summary><p className="muted">Сессия <code>{board.id}</code> · версия {board.version}. Отказы, замены и приёмки сохранены в PostgreSQL; прежние версии неизменяемы.</p>
      <p className="muted">Исходный snapshot <code>{board.sourceSnapshotId}</code> · <code>{board.snapshotHash}</code></p>
      <p className="muted">Hash оперативной версии <code>{board.stateHash}</code></p>
      <a href={`/?operations=${board.id}`}>Ссылка для восстановления этого сценария</a>
    </details>
  </section>
}
