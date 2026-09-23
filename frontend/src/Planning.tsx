import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Conflict, Plan } from './api'

const FAILURES = [
  ['MACHINE_DOWN', 'Станок недоступен на 12 часов'],
  ['UNPLANNED_INSPECTION', 'Внеплановый осмотр']
] as const

// Only these solver outcomes yield a plan that may be approved (ТЗ: UNKNOWN /
// INFEASIBLE / MODEL_INVALID never do).
const APPROVABLE = ['OPTIMAL', 'FEASIBLE']

// Why the approve button is off, or null if the plan can be approved.
// ponytail: UI-only guard — the API itself does not refuse approval yet; add a
// server-side check (422) when the real validator produces CRITICAL violations.
function blocker(plan: Plan, solver: string): string | null {
  if (plan.status === 'APPROVED') return `Уже согласован пользователем ${plan.approvedBy}`
  if (!APPROVABLE.includes(solver)) return `Расчёт не дал допустимого плана (${solver || 'нет статуса'})`
  if (plan.validations.some((v) => v.severity === 'CRITICAL')) return 'Есть критические нарушения'
  return null
}

// Keyed by scenarioId in the parent: injecting a failure creates a new scenario
// version, which remounts this and drops the now-outdated plan.
export default function Planning({
  scenarioId,
  onScenarioChange
}: {
  scenarioId: string
  onScenarioChange: (id: string) => void
}) {
  const qc = useQueryClient()
  const [jobId, setJobId] = useState<string | null>(null)
  const [failure, setFailure] = useState<string>(FAILURES[0][0])
  const [comment, setComment] = useState('')

  const fail = useMutation({
    mutationFn: () => api.addEvent(scenarioId, failure, FAILURES.find((f) => f[0] === failure)![1]),
    onSuccess: (r) => onScenarioChange(r.newScenarioId)
  })

  const start = useMutation({ mutationFn: () => api.startJob(scenarioId), onSuccess: (j) => setJobId(j.jobId) })

  // The contract is async (QUEUED/RUNNING -> terminal); poll until it settles.
  const job = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api.getJob(jobId!),
    enabled: !!jobId,
    refetchInterval: (q) => (['QUEUED', 'RUNNING'].includes(q.state.data?.status ?? '') ? 1000 : false)
  })
  const running = start.isPending || ['QUEUED', 'RUNNING'].includes(job.data?.status ?? '')

  const planId = job.data?.planId
  const plan = useQuery({ queryKey: ['plan', planId], queryFn: () => api.getPlan(planId!), enabled: !!planId })

  const approve = useMutation({
    mutationFn: () => api.approve(planId!, plan.data!.version, comment),
    onSuccess: (p) => qc.setQueryData(['plan', planId], p),
    onError: (e) => {
      if (e instanceof Conflict) qc.invalidateQueries({ queryKey: ['plan', planId] })
    }
  })

  const p = plan.data
  const solver = job.data?.solverStatus ?? ''
  const blocked = p ? blocker(p, solver) : null
  const error = [fail, start, job, plan, approve].find((q) => q.isError)?.error

  return (
    <section className="card pad">
      <h2>Сбой и согласование</h2>

      <div className="row">
        <select value={failure} onChange={(e) => setFailure(e.target.value)} aria-label="Тип сбоя">
          {FAILURES.map(([k, label]) => (
            <option key={k} value={k}>
              {label}
            </option>
          ))}
        </select>
        <button className="secondary" onClick={() => fail.mutate()} disabled={fail.isPending}>
          Ввести сбой
        </button>
        <button onClick={() => start.mutate()} disabled={running}>
          {running ? 'Расчёт…' : p ? 'Пересчитать план' : 'Рассчитать план'}
        </button>
      </div>
      <p className="muted">Сбой добавляется в сценарий как новая версия — реальные системы не затрагиваются. После него нужен пересчёт.</p>

      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
      {['FAILED', 'CANCELLED'].includes(job.data?.status ?? '') && (
        <p className="error">Расчёт завершился со статусом {job.data?.status}</p>
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

          <h3>Нарушения</h3>
          {p.validations.length === 0 ? (
            <p className="muted">Нарушений нет.</p>
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
        </>
      )}
    </section>
  )
}
