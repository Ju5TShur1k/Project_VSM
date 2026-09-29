import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Incident, ru } from './api'
import PlanningCalendar from './calendar/PlanningCalendar'
import { Icon } from './Icons'

const KINDS: Incident['kind'][] = ['TRIP_CHANGE', 'URGENT_MAINTENANCE', 'EQUIPMENT_DOWN']

const time = (iso: string) =>
  new Date(iso).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

// Shared journal: the dispatcher writes it, the planner reads it before recalculating.
// Polled so a report shows up on the planner's screen without a reload.
export function IncidentLog() {
  const incidents = useQuery({ queryKey: ['incidents'], queryFn: api.incidents, refetchInterval: 10_000 })
  if (!incidents.data?.length) return <p className="muted empty">Сообщений нет.</p>
  return (
    <table>
      <thead>
        <tr>
          <th>Время</th>
          <th>Состав</th>
          <th>Событие</th>
          <th>Описание</th>
          <th>Кто</th>
        </tr>
      </thead>
      <tbody>
        {incidents.data.map((i) => (
          <tr key={i.id}>
            <td>{time(i.reportedAt)}</td>
            <td>{i.train}</td>
            <td>
              <span className={`badge ${i.kind === 'TRIP_CHANGE' ? 'WARNING' : 'CRITICAL'}`}>{ru(i.kind)}</span>
            </td>
            <td>{i.description}</td>
            <td>{i.reportedBy}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default function Dispatcher() {
  const qc = useQueryClient()
  const [train, setTrain] = useState('')
  const [kind, setKind] = useState<Incident['kind']>('URGENT_MAINTENANCE')
  const [description, setDescription] = useState('')

  const current = useQuery({ queryKey: ['current-plan'], queryFn: api.currentPlan, refetchInterval: 15_000 })
  const planId = current.data?.planId
  const plan = useQuery({ queryKey: ['plan', planId], queryFn: () => api.getPlan(planId!), enabled: !!planId })
  const calendar = useQuery({ queryKey: ['calendar', planId], queryFn: () => api.getCalendar(planId!), enabled: !!planId })

  const report = useMutation({
    mutationFn: () => api.reportIncident(train, kind, description),
    onSuccess: () => {
      setDescription('')
      qc.invalidateQueries({ queryKey: ['incidents'] })
    }
  })

  return (
    <>
      <section className="card pad">
        <h2><Icon name="alert" />Сообщить о событии</h2>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault()
            report.mutate()
          }}
        >
          <label>Состав
            <input list="trains" value={train} onChange={(e) => setTrain(e.target.value)} required placeholder="CASE-01" />
            <datalist id="trains">
              {calendar.data?.trains.map((t) => <option key={t.id} value={t.label} />)}
            </datalist>
          </label>
          <label>Событие
            <select value={kind} onChange={(e) => setKind(e.target.value as Incident['kind'])}>
              {KINDS.map((k) => <option key={k} value={k}>{ru(k)}</option>)}
            </select>
          </label>
          <label className="grow">Что произошло
            <input value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={500}
              placeholder="Например: рейс задержан на 40 мин / замечание машиниста" />
          </label>
          <button disabled={report.isPending}>{report.isPending ? 'Отправка…' : 'Отправить'}</button>
        </form>
        {report.isSuccess && <p className="muted">Сообщение передано планировщику.</p>}
        {report.isError && <p className="error">{report.error.message}</p>}
      </section>

      <section className="card">
        <h2 className="pad-h"><Icon name="bell" />Журнал сообщений</h2>
        <IncidentLog />
      </section>

      <section className="card pad">
        <h2><Icon name="plan" />Текущий план</h2>
        {!planId && <p className="muted">План ещё не рассчитан.</p>}
        {plan.data && (
          <p>
            <span className={`badge ${plan.data.status}`}>{ru(plan.data.status)}</span>
            {plan.data.approvedBy && <span className="muted"> · согласовал {plan.data.approvedBy}</span>}
          </p>
        )}
        {calendar.data && <PlanningCalendar data={calendar.data} details={false} />}
      </section>
    </>
  )
}
