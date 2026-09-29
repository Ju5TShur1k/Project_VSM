import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, FullDraft, ru } from './api'
import type { Ctx } from './App'
import { Icon, Info } from './Icons'
import { diffPlans } from './planDiff'
import { clock, time } from './format'

// Excel in Russian locale saves CSV as Windows-1251; everything else is UTF-8.
async function readCsv(file: File) {
  const bytes = await file.arrayBuffer()
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes) } catch { return new TextDecoder('windows-1251').decode(bytes) }
}

const CSV_HELP = 'В CSV укажите рейс, состав и время отправления и прибытия по Москве. Для нового рейса добавьте города и расстояние.'

export default function PlanTab({ ws, setWs, rootId, head, planId, calendar, proposals, onRequests }: Ctx & { onRequests: () => void }) {
  const qc = useQueryClient()
  const source = useQuery({ queryKey: ['source', head?.scenarioId], queryFn: () => api.source(head!.scenarioId), enabled: !!head, staleTime: Infinity })
  const trips = source.data?.fixedTrips.length
  const horizonStart = source.data?.scenario.horizon_start

  const load = useMutation({
    mutationFn: api.loadFleet,
    onSuccess: (r) => { setWs({ rootId: r.rootId, operationsId: r.operationsId }); qc.invalidateQueries() }
  })
  const upload = useMutation({
    mutationFn: async (file: File) => api.uploadSchedule(rootId!, await readCsv(file)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['head'] })
  })

  // Building takes about a minute: show the elapsed time instead of a frozen button.
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const [, tick] = useState(0)
  useEffect(() => { if (!startedAt) return; const t = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(t) }, [startedAt])
  const build = useMutation({
    mutationFn: () => api.buildPlan(head!.snapshotId, horizonStart!),
    onMutate: () => setStartedAt(Date.now()),
    onSettled: () => setStartedAt(null),
    onSuccess: (d) => {
      // Keep only what the tiles show; the full answer lists every cleaning and reassignment.
      const { snapshotHash, searchStatus, moves, tripCount, changedTripCount, requiredCleaningCount, placedBlockCount,
        modelSolverStatus, structuralStatus, d2Status, reserve } = d
      const draft: FullDraft = { planId: d.planId, snapshotHash, searchStatus, moves, tripCount, changedTripCount, requiredCleaningCount,
        placedBlockCount, modelSolverStatus, structuralStatus, d2Status, reserve, diagnostics: [] }
      setWs((w) => ({ ...w, draft, planId: d.planId ?? w.planId, previousPlanId: d.planId && planId && d.planId !== planId ? planId : w.previousPlanId }))
      qc.invalidateQueries({ queryKey: ['selection'] })
    }
  })

  if (!rootId) return (
    <section className="card pad">
      <h2><Icon name="database" />Парк<Info text="Демонстрационный парк: 43 состава и расписание на 14 дней." /></h2>
      <button onClick={() => load.mutate()} disabled={load.isPending}>{load.isPending ? 'Загрузка…' : 'Загрузить парк: 43 состава'}</button>
      {load.isError && <p className="error">{load.error.message}</p>}
    </section>
  )

  const draft = ws.draft && calendar && ws.draft.snapshotHash === calendar.snapshotHash ? ws.draft : undefined
  const stale = !!head && !!calendar && head.snapshotHash !== calendar.snapshotHash
  const waiting = proposals.filter((p) => p.status === 'PENDING').length
  const services = calendar?.events.filter((e) => e.kind === 'SERVICE') ?? []
  const cleanings = services.filter((e) => e.label.startsWith('Уборка')).length
  const spb = draft?.reserve.cities.find((c) => c.name === 'SPB_DEPOT')

  return (
    <>
      <section className="card pad">
        <div className="bar">
          <h2><Icon name="database" />Исходные данные<Info text="Здесь видно, по какой версии расписания построен план." /></h2>
          <div className="actions">
            <label className="btn-outline">Загрузить расписание CSV
              <input type="file" accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = '' }} />
            </label>
            <Info text={CSV_HELP} />
          </div>
        </div>
        <p>
          <strong>43 состава · {trips ?? '…'} рейсов · 14 суток</strong>
          {head && <span className="muted"> · версия {head.version} · <code title={head.snapshotHash}>{head.snapshotHash.slice(0, 12)}</code></span>}
        </p>
        {upload.isPending && <p className="progress">Загрузка расписания…</p>}
        {upload.isSuccess && <p className="banner ok">Расписание загружено: изменено рейсов {upload.data.changed}, добавлено {upload.data.added}, без изменений {upload.data.unchanged}. Постройте план по новой версии.</p>}
        {upload.isError && <p className="error">{upload.error.message}</p>}
      </section>

      <section className="card pad">
        <div className="bar">
          <h2><Icon name="plan" />План ТО на 14 суток<Info text="Система подбирает время ТО и уборки. При необходимости передаёт рейсы резервному составу." /></h2>
          <button onClick={() => build.mutate()} disabled={!head || !horizonStart || build.isPending}>
            {build.isPending ? `Расчёт… ${Math.round((Date.now() - (startedAt ?? Date.now())) / 1000)} с` : calendar ? 'Пересчитать' : 'Построить план'}
          </button>
        </div>

        {stale && <p className="banner">Данные изменились после расчёта (версия {head!.version}). Пересчитайте план.</p>}
        {waiting > 0 && <p className="banner">Заявок ждут решения диспетчера: {waiting}. <button className="link" onClick={onRequests}>Открыть заявки</button></p>}
        {build.isPending && <p className="progress">Подбор замен рейсов и размещение ТО обычно занимает 1–2 минуты.</p>}
        {build.isError && <p className="error">{build.error.message}</p>}
        {build.data && !build.data.planId && (
          <div className="banner">
            <strong>План не построен.</strong> Длительным ТО не хватает окна даже после передачи рейсов резерву:
            <ul className="viol">{build.data.diagnostics.slice(0, 6).map((d, i) => <li key={i}>{d.message}</li>)}</ul>
          </div>
        )}

        {calendar && (
          <div className="tiles">
            <div><strong>{services.length - cleanings}</strong><span>работ ТО</span></div>
            <div><strong>{cleanings}</strong><span>уборок</span></div>
            {draft && <div><strong>{draft.changedTripCount}</strong><span>рейсов передано резерву</span></div>}
            {spb && <div className={spb.deficit ? 'warn' : ''}><strong>{spb.remaining}/{spb.sourceTarget}</strong><span>резерв СПб</span></div>}
            {draft && <div className="txt"><strong>{ru(draft.modelSolverStatus)}</strong><span>результат расчёта<Info text="Показывает, удалось ли составить план и найти лучшее размещение работ." /></span></div>}
            <div className="txt"><strong>{ru(draft?.structuralStatus ?? 'PASS')}</strong><span>проверка интервалов<Info text="Проверено, что рейсы и работы не пересекаются. До полной проверки план остаётся черновиком." /></span></div>
          </div>
        )}
        {calendar && <p className="muted">Построен по версии данных <code>{calendar.snapshotHash.slice(0, 12)}</code>. Календарь и поиск — во вкладке «Парк».</p>}
      </section>

      {ws.previousPlanId && planId && ws.previousPlanId !== planId && <Changes beforeId={ws.previousPlanId} afterId={planId} />}
    </>
  )
}

function Changes({ beforeId, afterId }: { beforeId: string; afterId: string }) {
  const before = useQuery({ queryKey: ['calendar', beforeId], queryFn: () => api.calendar(beforeId), staleTime: Infinity })
  const after = useQuery({ queryKey: ['calendar', afterId], queryFn: () => api.calendar(afterId), staleTime: Infinity })
  const changes = useMemo(() => diffPlans(before.data, after.data), [before.data, after.data])
  if (!before.data || !after.data) return null
  const name = (id: string) => after.data!.trains.find((t) => t.id === id)?.label ?? before.data!.trains.find((t) => t.id === id)?.label ?? id
  const label = { MOVED: 'Сдвиг', REASSIGNED: 'Другой состав', ADDED: 'Новое', REMOVED: 'Снято' }
  return (
    <section className="card">
      <h2 className="pad-h"><Icon name="route" />Изменения после пересчёта · {changes.length}
        <Info text="Здесь показано, что изменилось после пересчёта." /></h2>
      {changes.length === 0 ? <p className="muted empty">План не изменился.</p> : (
        <div className="table-scroll">
          <table>
            <thead><tr><th>Состав</th><th>Что</th><th>Изменение</th><th>Было</th><th>Стало</th></tr></thead>
            <tbody>
              {changes.slice(0, 60).map((c, i) => (
                <tr key={i}>
                  <td>{name(c.trainId)}</td>
                  <td>{c.label.replace(/^Рейс /, '').replace(/^MODEL-/, '')}</td>
                  <td><span className={`badge ${c.kind === 'REMOVED' ? 'CRITICAL' : 'WARNING'}`}>{label[c.kind]}</span></td>
                  <td>{c.before ? `${time(c.before.startAt)}–${clock(c.before.endAt)}${c.kind === 'REASSIGNED' ? ' · ' + name(c.before.trainId) : ''}` : '—'}</td>
                  <td>{c.after ? `${time(c.after.startAt)}–${clock(c.after.endAt)}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {changes.length > 60 && <p className="muted empty">Показаны первые 60 из {changes.length}.</p>}
    </section>
  )
}
