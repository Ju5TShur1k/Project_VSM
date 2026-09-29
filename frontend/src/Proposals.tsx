import { Fragment, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, newId, Proposal, ProposalPayload, Role, ru, SourcePayload } from './api'
import type { Ctx } from './App'
import { Icon, Info } from './Icons'
import { clock, shift, time, toInput, toIso } from './format'

const REASONS = ['Задержка отправления', 'Задержка прибытия', 'Изменение графика движения', 'Иное']

export default function Proposals({ rootId, operationsId, head, calendar, proposals, role, onPlan }: Ctx & { role: Role; onPlan: () => void }) {
  const source = useQuery({ queryKey: ['source', head?.scenarioId], queryFn: () => api.source(head!.scenarioId), enabled: !!head, staleTime: Infinity })
  const [open, setOpen] = useState<string | null>(null)
  const planned = !!calendar && !!head && calendar.snapshotHash === head.snapshotHash

  if (!rootId) return <p className="banner">{role === 'PLANNER' ? 'Сначала загрузите парк во вкладке «План ТО».' : 'Заявок пока нет.'}</p>

  return (
    <>
      {role === 'PLANNER' && source.data && <NewProposal rootId={rootId} operationsId={operationsId} source={source.data} />}
      <section className="card">
        <h2 className="pad-h"><Icon name="bell" />Заявки
          <Info text={role === 'PLANNER'
            ? 'Заявка ничего не меняет, пока её не согласует диспетчер. Согласованное изменение рейса создаёт новую версию данных — после этого пересчитайте план.'
            : 'Согласуйте или отклоните заявку планировщика. Для отказа состава выберите замену из резерва: система показывает, кто подходит и почему.'} />
        </h2>
        {proposals.length === 0 ? <p className="muted empty">Заявок нет.</p> : (
          <div className="table-scroll">
            <table>
              <thead><tr><th>№</th><th>Заявка</th><th>Статус</th><th>Решение</th><th></th></tr></thead>
              <tbody>
                {proposals.map((p) => (
                  <Fragment key={p.id}>
                    <Row p={p} src={source.data} role={role} planned={planned} headVersion={head?.version}
                      open={open === p.id} onToggle={() => setOpen(open === p.id ? null : p.id)} onPlan={onPlan} />
                    {open === p.id && role === 'DISPATCHER' && p.status === 'PENDING' && (
                      <tr className="expand"><td colSpan={5}><Review p={p} src={source.data} onDone={() => setOpen(null)} /></td></tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function describe(p: Proposal, src?: SourcePayload) {
  const train = src?.trains.find((t) => t.id === p.payload.trainId)?.external_id ?? '…'
  const trip = src?.fixedTrips.find((t) => t.id === p.payload.tripId)?.label.replace(/^MODEL-/, '') ?? ''
  if (p.payload.kind === 'TRIP_CHANGE')
    return `${train} · рейс ${trip}: ${time(p.payload.newDepartureAt)}–${clock(p.payload.newArrivalAt)} · ${p.payload.reason}`
  return `${train} · отказ перед рейсом ${trip} · ${p.payload.description}`
}

function Row({ p, src, role, planned, headVersion, open, onToggle, onPlan }: {
  p: Proposal; src?: SourcePayload; role: Role; planned: boolean; headVersion?: number; open: boolean; onToggle: () => void; onPlan: () => void
}) {
  const applied = p.status === 'APPROVED' && p.applied
  const inPlan = applied && planned && headVersion !== undefined && p.applied!.sourceVersion <= headVersion
  return (
    <tr className={open ? 'current' : ''}>
      <td>{p.number}</td>
      <td><span className={`badge ${p.payload.kind}`}>{ru(p.payload.kind)}</span> {describe(p, src)}
        <br /><span className="muted">{p.createdBy}, {time(p.createdAt)}{p.comment && ` · ${p.comment}`}</span></td>
      <td>
        <span className={`badge ${p.status}`}>{ru(p.status)}</span>
        {applied && <><br /><span className="muted">{inPlan ? 'учтена в плане' : 'нужен пересчёт'}</span></>}
      </td>
      <td>{p.reviewedBy ? <>{p.reviewedBy}, {time(p.reviewedAt!)}{p.reviewComment && <><br /><span className="muted">{p.reviewComment}</span></>}</> : '—'}</td>
      <td>
        {role === 'DISPATCHER' && p.status === 'PENDING' && <button className="btn-outline" onClick={onToggle}>{open ? 'Свернуть' : 'Рассмотреть'}</button>}
        {role === 'PLANNER' && applied && !inPlan && <button onClick={onPlan}>Пересчитать</button>}
      </td>
    </tr>
  )
}

// Planner: a trip time change or a train failure before departure. One id per filled
// form, so a double click cannot file the same request twice.
function NewProposal({ rootId, operationsId, source }: { rootId: string; operationsId?: string; source: SourcePayload }) {
  const qc = useQueryClient()
  const [kind, setKind] = useState<ProposalPayload['kind']>('TRIP_CHANGE')
  const [clientId, setClientId] = useState(newId)
  const trains = [...source.trains].sort((a, b) => a.external_id.localeCompare(b.external_id, 'ru', { numeric: true }))
    .filter((t) => source.fixedTrips.some((x) => x.train_id === t.id))
  const [trainId, setTrainId] = useState(trains[0]?.id ?? '')
  const tripsOf = (id: string) => source.fixedTrips.filter((t) => t.train_id === id).sort((a, b) => a.departure_at.localeCompare(b.departure_at))
  const [tripId, setTripId] = useState(tripsOf(trainId)[0]?.id ?? '')
  const trip = source.fixedTrips.find((t) => t.id === tripId)
  const pickFailureTrip = (id: string) => { const t = boardTrips.find((x) => x.id === id); setTripId(id); if (t) setTrainId(t.effectiveTrainId) }
  const [dep, setDep] = useState(trip ? toInput(trip.departure_at) : '')
  const [arr, setArr] = useState(trip ? toInput(trip.arrival_at) : '')
  const [reason, setReason] = useState(REASONS[0])
  const [text, setText] = useState('')
  const [repair, setRepair] = useState(4)
  // A failure is handled on today's operations board: only its trips that have not left yet.
  const board = useQuery({ queryKey: ['board', operationsId], queryFn: () => api.board(operationsId!), enabled: !!operationsId && kind === 'TRAIN_FAILURE' })
  const boardTrips = (board.data?.trips ?? []).filter((t) => t.coverage !== 'UNCOVERED' && Date.parse(t.departureAt) > Date.parse(board.data!.asOf))
    .sort((a, b) => a.departureAt.localeCompare(b.departureAt))

  const pickTrip = (id: string) => {
    const t = source.fixedTrips.find((x) => x.id === id)
    setTripId(id)
    if (t) { setDep(toInput(t.departure_at)); setArr(toInput(t.arrival_at)) }
  }
  const pickTrain = (id: string) => { setTrainId(id); pickTrip(tripsOf(id)[0]?.id ?? '') }

  const send = useMutation({
    mutationFn: () => {
      // A failure is noticed an hour before departure, but never before the board's "now".
      const failedAt = trip && board.data ? Math.max(Date.parse(board.data.asOf), Date.parse(trip.departure_at) - 60 * 60_000) : 0
      const payload: ProposalPayload = kind === 'TRIP_CHANGE'
        ? { kind, trainId, tripId, newDepartureAt: toIso(dep), newArrivalAt: toIso(arr), reason, source: 'Планировщик' }
        : { kind, trainId, tripId, occurredAt: new Date(failedAt).toISOString(),
            expectedRepairAt: new Date(failedAt + repair * 60 * 60_000).toISOString(),
            description: text || 'Отказ оборудования, выпуск запрещён', operationsId: operationsId! }
      return api.propose(rootId, payload, kind === 'TRIP_CHANGE' ? text : '', clientId)
    },
    onSuccess: () => { setClientId(newId()); setText(''); qc.invalidateQueries({ queryKey: ['proposals'] }) }
  })

  const unchanged = trip && toIso(dep) === toIso(toInput(trip.departure_at)) && toIso(arr) === toIso(toInput(trip.arrival_at))
  const invalid = !trip ? 'Выберите рейс'
    : kind === 'TRIP_CHANGE' && Date.parse(toIso(arr)) <= Date.parse(toIso(dep)) ? 'Прибытие должно быть позже отправления'
    : kind === 'TRIP_CHANGE' && unchanged ? 'Измените время'
    : kind === 'TRAIN_FAILURE' && !operationsId ? 'Перезагрузите парк во вкладке «План ТО»'
    : kind === 'TRAIN_FAILURE' && !boardTrips.some((t) => t.id === tripId) ? 'Выберите рейс текущих суток' : null

  return (
    <section className="card pad">
      <h2><Icon name="alert" />Новая заявка<Info text="Изменение рейса — новое время отправления и прибытия. Отказ состава — поломка перед рейсом: диспетчер снимает состав и назначает замену из резерва." /></h2>
      <div className="seg" role="tablist">
        {(['TRIP_CHANGE', 'TRAIN_FAILURE'] as const).map((k) => (
          <button key={k} role="tab" aria-selected={kind === k} className={kind === k ? 'on' : ''} onClick={() => setKind(k)}>{ru(k)}</button>
        ))}
      </div>
      <form className="event-form" onSubmit={(e) => {
        e.preventDefault()
        if (invalid || !trip) return
        const what = kind === 'TRIP_CHANGE' ? `${trip.label}: ${dep.slice(11)}–${arr.slice(11)}` : `отказ ${trains.find((t) => t.id === trainId)?.external_id} перед ${trip.label}`
        if (confirm(`Отправить заявку диспетчеру?\n${what}`)) send.mutate()
      }}>
        {kind === 'TRAIN_FAILURE' ? (
          <label>Рейс и состав (текущие сутки)
            <select value={boardTrips.some((t) => t.id === tripId) ? tripId : ''} onChange={(e) => pickFailureTrip(e.target.value)}>
              <option value="" disabled>Выберите рейс</option>
              {boardTrips.map((t) => <option key={t.id} value={t.id}>{t.effectiveTrain} · {time(t.departureAt)} · {ru(t.origin)} → {ru(t.destination)} · {t.label.replace(/^MODEL-/, '')}</option>)}
            </select>
          </label>
        ) : <div className="row">
          <label>Состав
            <select value={trainId} onChange={(e) => pickTrain(e.target.value)}>
              {trains.map((t) => <option key={t.id} value={t.id}>{t.external_id}</option>)}
            </select>
          </label>
          <label className="grow">Рейс
            <select value={tripId} onChange={(e) => pickTrip(e.target.value)}>
              {tripsOf(trainId).map((t) => <option key={t.id} value={t.id}>{time(t.departure_at)} · {ru(t.origin)} → {ru(t.destination)} · {t.label.replace(/^MODEL-/, '')}</option>)}
            </select>
          </label>
        </div>}
        {kind === 'TRIP_CHANGE' ? (
          <div className="row">
            <label>Отправление (МСК)<input type="datetime-local" value={dep} onChange={(e) => setDep(e.target.value)} required /></label>
            <label>Прибытие (МСК)<input type="datetime-local" value={arr} onChange={(e) => setArr(e.target.value)} required /></label>
            <button type="button" className="btn-outline" onClick={() => { setDep(shift(dep, 5)); setArr(shift(arr, 5)) }}>+5 мин</button>
            <label>Причина<select value={reason} onChange={(e) => setReason(e.target.value)}>{REASONS.map((r) => <option key={r}>{r}</option>)}</select></label>
          </div>
        ) : (
          <div className="row">
            <label>Ремонт, ч<input type="number" min={1} max={72} value={repair} onChange={(e) => setRepair(Number(e.target.value))} style={{ width: 90 }} /></label>
            <label className="grow">Что случилось<input value={text} onChange={(e) => setText(e.target.value)} maxLength={500} placeholder="Отказ тягового оборудования" /></label>
          </div>
        )}
        {kind === 'TRIP_CHANGE' && <label>Комментарий<input value={text} onChange={(e) => setText(e.target.value)} maxLength={500} placeholder="Необязательно" /></label>}
        {invalid && trip && <p className="muted">{invalid}</p>}
        {send.isError && <p className="error">{send.error.message}</p>}
        {send.isSuccess && <p className="banner ok">Заявка № {send.data.number} отправлена диспетчеру.</p>}
        <button disabled={!!invalid || send.isPending}>{send.isPending ? 'Отправка…' : 'Отправить диспетчеру'}</button>
      </form>
    </section>
  )
}

// Dispatcher: approve / reject; for a failure, first choose a replacement from reserve.
function Review({ p, src, onDone }: { p: Proposal; src?: SourcePayload; onDone: () => void }) {
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const failure = p.payload.kind === 'TRAIN_FAILURE' ? p.payload : null
  const board = useQuery({ queryKey: ['board', failure?.operationsId], queryFn: () => api.board(failure!.operationsId), enabled: !!failure })
  const fault = board.data?.faults.find((f) => f.trainId === p.payload.trainId && !f.replacementTrain)
  const done = () => { qc.invalidateQueries({ queryKey: ['proposals'] }); qc.invalidateQueries({ queryKey: ['head'] }); onDone() }
  const approve = useMutation({ mutationFn: (comment: string) => api.approveProposal(p.id, comment), onSuccess: done })
  const reject = useMutation({ mutationFn: () => api.rejectProposal(p.id, reason), onSuccess: done })
  const register = useMutation({
    mutationFn: () => api.boardCommand(board.data!, 'failures', { tripId: failure!.tripId, occurredAt: failure!.occurredAt, expectedRepairAt: failure!.expectedRepairAt, description: failure!.description }),
    onSuccess: (b) => qc.setQueryData(['board', failure!.operationsId], b)
  })
  const replace = useMutation({
    mutationFn: async (c: { trainId: string; externalId: string }) => {
      const b = await api.boardCommand(board.data!, 'replacements', { faultId: fault!.id, trainId: c.trainId })
      qc.setQueryData(['board', failure!.operationsId], b)
      return api.approveProposal(p.id, `Замена: ${c.externalId}`)
    },
    onSuccess: done
  })
  const error = [approve, reject, register, replace].find((m) => m.isError)?.error

  return (
    <div className="review">
      <h3>Заявка № {p.number}: {describe(p, src)}</h3>
      {failure && board.data && (
        <>
          <div className="row">
            {board.data.reserve.map((r) => <span key={r.location} className={`badge ${r.deficit ? 'CRITICAL' : 'APPROVED'}`}>Резерв {ru(r.location)}: {r.available}/{r.target}</span>)}
          </div>
          {!fault ? (
            <p><button onClick={() => register.mutate()} disabled={register.isPending}>Принять отказ и подобрать замену</button>
              <Info text="Состав снимается со всех оставшихся рейсов суток; система проверяет кандидатов из резерва по городу, уборке, пробегу и подготовке." /></p>
          ) : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Кандидат</th><th>Обоснование</th><th></th></tr></thead>
                <tbody>
                  {fault.candidates.map((c) => (
                    <tr key={c.trainId}>
                      <td><strong>{c.externalId}</strong><br /><span className="muted">{ru(c.location)}</span></td>
                      <td className="muted">{c.reasons.join('; ').replace(/SPB_DEPOT/g, 'Санкт-Петербург').replace(/MOSCOW/g, 'Москва')}</td>
                      <td>{c.eligible ? <button disabled={replace.isPending} onClick={() => replace.mutate(c)}>Назначить</button> : <span className="error">Не подходит</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      <div className="row approve">
        {!failure && <button onClick={() => approve.mutate('')} disabled={approve.isPending}>Согласовать</button>}
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Причина отклонения" aria-label="Причина отклонения" />
        <button className="btn-outline" disabled={!reason.trim() || reject.isPending} onClick={() => reject.mutate()}>Отклонить</button>
      </div>
      {error && <p className="error">{error.message}</p>}
    </div>
  )
}
