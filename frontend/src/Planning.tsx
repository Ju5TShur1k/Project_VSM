import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Conflict, Plan, Train } from './api'
import PlanningCalendar from './calendar/PlanningCalendar'

// Only these solver outcomes yield a plan that may be approved (ТЗ: UNKNOWN /
// INFEASIBLE / MODEL_INVALID never do).
const APPROVABLE = ['OPTIMAL', 'FEASIBLE']

// Why the approve button is off, or null if the plan can be approved. Mirrors the
// server, which independently answers 422 for a plan with CRITICAL violations.
function blocker(plan: Plan, solver: string): string | null {
  if (plan.status === 'APPROVED') return `Уже согласован пользователем ${plan.approvedBy}`
  if (!APPROVABLE.includes(solver)) return `Расчёт не дал допустимого плана (${solver || 'нет статуса'})`
  if (plan.validationStatus !== 'PASS') return `Проверка D2: ${plan.validationStatus}`
  if (plan.validations.some((v) => v.severity === 'CRITICAL')) return 'Есть критические нарушения'
  return null
}

const fmt = (iso: string) =>
  new Date(iso).toLocaleString('ru-RU', {
    timeZone: 'Europe/Moscow',
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })

// Keyed by scenarioId in the parent: injecting a failure creates a new scenario
// version, which remounts this and drops the now-outdated plan.
export default function Planning({
  scenarioId,
  trains
}: {
  scenarioId: string
  trains: Train[] | undefined
}) {
  const qc = useQueryClient()
  const [jobId, setJobId] = useState<string | null>(null)
  const [comment, setComment] = useState('')

  const start = useMutation({ mutationFn: () => api.startJob(scenarioId), onSuccess: (j) => setJobId(j.jobId) })

  // The contract is async (QUEUED/RUNNING -> terminal); poll until it settles.
  // Also in a background tab: otherwise a user who switches away mid-solve
  // comes back to a job that looks stuck.
  const job = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api.getJob(jobId!),
    enabled: !!jobId,
    refetchInterval: (q) => (['QUEUED', 'RUNNING'].includes(q.state.data?.status ?? '') ? 1000 : false),
    refetchIntervalInBackground: true
  })
  const running = start.isPending || ['QUEUED', 'RUNNING'].includes(job.data?.status ?? '')

  const planId = job.data?.planId
  const plan = useQuery({ queryKey: ['plan', planId], queryFn: () => api.getPlan(planId!), enabled: !!planId })
  const calendar = useQuery({ queryKey: ['calendar', planId], queryFn: () => api.getCalendar(planId!), enabled: !!planId })

  const approve = useMutation({
    mutationFn: () => api.approve(planId!, plan.data!.version, comment),
    onSuccess: (p) => qc.setQueryData(['plan', planId], p),
    onError: (e) => {
      if (e instanceof Conflict) qc.invalidateQueries({ queryKey: ['plan', planId] })
    }
  })

  const p = plan.data
  const trainName = new Map(trains?.map((t) => [t.id, t.externalId]))
  const solver = job.data?.solverStatus ?? ''
  const blocked = p ? blocker(p, solver) : null
  const error = [start, job, plan, calendar, approve].find((q) => q.isError)?.error

  return (
    <section className="card pad">
      <h2>Расчёт планировщика → проверка D2 → календарь</h2>

      <div className="row">
        <button onClick={() => start.mutate()} disabled={running}>
          {running ? 'Расчёт…' : p ? 'Пересчитать план' : 'Рассчитать план'}
        </button>
      </div>
      <p className="muted">Расчёт использует hash сохранённого snapshot. Результат и календарь относятся к этому hash.</p>
      <dl className="kv">
        <dt>Задание</dt><dd>{job.data?.status ?? (jobId ? 'Загрузка…' : 'Не запускалось')}</dd>
        <dt>Расчёт</dt><dd>{job.data?.solverStatus ?? 'Не выполнялся'}</dd>
        <dt>Проверка D2</dt><dd>{p?.validationStatus ?? 'Не проводилась'}</dd>
      </dl>

      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
      {['FAILED', 'CANCELLED'].includes(job.data?.status ?? '') && (
        <p className="error">
          Расчёт завершился со статусом {job.data?.status}
          {job.data?.error && `: ${job.data.error}`}
        </p>
      )}

      {p && (
        <>
          <dl className="kv">
            <dt>Версия</dt>
            <dd>{p.version}</dd>
            <dt>Статус</dt>
            <dd>
              <span className={`badge ${p.status}`}>{p.status}</span>
            </dd>
            <dt>Hash snapshot результата</dt>
            <dd><code>{p.snapshotHash}</code></dd>
            <dt>Солвер</dt>
            <dd>
              <span className={`badge ${solver}`}>{solver || '—'}</span>
            </dd>
            <dt>Работ в плане</dt>
            <dd>{p.events.length}</dd>
            {p.approvedBy && (
              <>
                <dt>Согласовал</dt>
                <dd>{p.approvedBy}</dd>
              </>
            )}
          </dl>

          {p.events.length > 0 && (
            <details>
              <summary>Работы в плане ({p.events.length})</summary>
              <table>
                <thead>
                  <tr>
                    <th>Поезд</th>
                    <th>Путь</th>
                    <th>Начало</th>
                    <th>Конец</th>
                  </tr>
                </thead>
                <tbody>
                  {p.events.map((e) => (
                    <tr key={e.id}>
                      <td>{trainName.get(e.trainId) ?? e.trainId}</td>
                      <td>{e.resourceIds.join(', ')}</td>
                      <td>{fmt(e.startAt)}</td>
                      <td>{fmt(e.endAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
          )}

          <h3>Результат проверки и диагностика</h3>
          {p.validations.length === 0 ? (
            <p className="muted">Критических замечаний нет. Статус D2: {p.validationStatus}.</p>
          ) : (
            <ul className="viol">
              {p.validations.map((v, i) => (
                <li key={i}>
                  <span className={`badge ${v.severity}`}>{v.severity}</span> {v.code}: {v.message}
                </li>
              ))}
            </ul>
          )}

          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault()
              approve.mutate()
            }}
          >
            <input
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Комментарий (необязательно)"
              aria-label="Комментарий"
              disabled={!!blocked}
            />
            <button disabled={!!blocked || approve.isPending}>Согласовать</button>
            <a href={`/api/v1/plans/${p.id}/export`} download>
              Скачать CSV
            </a>
          </form>
          {blocked && <p className="muted">{blocked}</p>}
          {calendar.data && <PlanningCalendar data={calendar.data} />}
        </>
      )}
    </section>
  )
}
