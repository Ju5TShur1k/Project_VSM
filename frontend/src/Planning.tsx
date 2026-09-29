import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Conflict, Plan, ru, Train } from './api'
import PlanningCalendar from './calendar/PlanningCalendar'

// Only these solver outcomes yield a plan that may be approved (ТЗ: UNKNOWN /
// INFEASIBLE / MODEL_INVALID never do).
const APPROVABLE = ['OPTIMAL', 'FEASIBLE']

// Why the approve button is off, or null if the plan can be approved. Mirrors the
// server: a missing D2 check (NOT_PERFORMED) is a caveat, not a blocker.
function blocker(plan: Plan, solver: string): string | null {
  if (plan.status === 'APPROVED') return null
  if (!APPROVABLE.includes(solver)) return 'Нет допустимого плана — согласовать нельзя'
  if (plan.validationStatus === 'FAILED') return 'Проверка D2 не пройдена'
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
  trains,
  canApprove,
  onPlan
}: {
  scenarioId: string
  trains: Train[] | undefined
  canApprove: boolean
  onPlan: (planId: string | undefined) => void
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

  const planId = job.data?.planId ?? undefined
  useEffect(() => onPlan(planId), [planId, onPlan])
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

  // The D2 caveat has its own line next to the approve button.
  const findings = p?.validations.filter((v) => v.code !== 'VALIDATION_NOT_PERFORMED') ?? []
  const noD2 = p?.validationStatus === 'NOT_PERFORMED'

  return (
    <section className="card pad">
      <h2>План ТО</h2>

      <button onClick={() => start.mutate()} disabled={running}>
        {running ? 'Расчёт…' : p ? 'Пересчитать' : 'Рассчитать план'}
      </button>

      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
      {['FAILED', 'CANCELLED'].includes(job.data?.status ?? '') && (
        <p className="error">Расчёт не выполнен{job.data?.error && `: ${job.data.error}`}</p>
      )}

      {p && (
        <>
          <dl className="kv">
            <dt>Результат</dt>
            <dd>
              <span className={`badge ${solver}`}>{ru(solver)}</span>
            </dd>
            <dt>Статус</dt>
            <dd>
              <span className={`badge ${p.status}`}>{ru(p.status)}</span>
              {p.approvedBy && ` · ${p.approvedBy}`}
            </dd>
            <dt>Проверка D2</dt>
            <dd>{ru(p.validationStatus)}</dd>
            <dt>Версия данных</dt>
            <dd title={p.snapshotHash}><code>{p.snapshotHash.slice(0, 12)}</code></dd>
          </dl>

          {findings.length > 0 && (
            <ul className="viol">
              {findings.map((v, i) => (
                <li key={i}>
                  <span className={`badge ${v.severity}`}>{v.severity}</span> {v.message}
                </li>
              ))}
            </ul>
          )}

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

          {canApprove && p.status !== 'APPROVED' && (
            <form
              className="row approve"
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
              <button disabled={!!blocked || approve.isPending}>
                {approve.isPending ? 'Согласуем…' : 'Согласовать'}
              </button>
              <a className="btn-outline" href={`/api/v1/plans/${p.id}/export`} download>
                Скачать CSV
              </a>
            </form>
          )}
          {!canApprove && p.status !== 'APPROVED' && <p className="muted">Согласует планировщик.</p>}
          {canApprove && blocked && <p className="error">{blocked}</p>}
          {canApprove && !blocked && noD2 && (
            <p className="muted">
              {p.status === 'APPROVED' ? 'Согласован без' : 'Согласование пройдёт без'} независимой проверки D2.
            </p>
          )}
          {(p.status === 'APPROVED' || !canApprove) && (
            <p><a className="btn-outline" href={`/api/v1/plans/${p.id}/export`} download>
              Скачать CSV
            </a></p>
          )}
          {calendar.data && <PlanningCalendar data={calendar.data} />}
        </>
      )}
    </section>
  )
}
