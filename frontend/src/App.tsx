import { useEffect, useState } from 'react'
import type { Dispatch, ReactNode, SetStateAction } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CaseDataset, DemoSource, RecoveryBoard, Role, Train, Unauthorized } from './api'
import RecoveryConsole from './RecoveryConsole'
import { Icon, Logo } from './Icons'
import Login from './Login'
import Planning from './Planning'
import CalendarDemo from './calendar/CalendarDemo'
import Events from './Events'
import Fleet from './Fleet'
import { InboxSection, PlanCompare, usePlanView } from './Requests'

type Tab = 'planning' | 'fleet' | 'events'
const TABS: Record<Role, Tab[]> = { PLANNER: ['planning', 'fleet', 'events'], TECHNOLOGIST: ['planning', 'fleet'], DISPATCHER: ['events', 'fleet'] }
const TAB_NAME: Record<Tab, string> = { planning: 'Планирование', fleet: 'Парк', events: 'События' }

// The open tab lives in the URL hash, so a reload or a shared link lands on it.
function useTab(role: Role): [Tab, (t: Tab) => void] {
  const pick = () => { const h = window.location.hash.slice(1) as Tab; return TABS[role].includes(h) ? h : TABS[role][0] }
  const [tab, setTab] = useState(pick)
  useEffect(() => { const on = () => setTab(pick()); window.addEventListener('hashchange', on); return () => window.removeEventListener('hashchange', on) })
  return [tab, (t) => { window.location.hash = t }]
}

// ponytail: the planner's working set (loaded dataset, last plan) is kept in this browser
// so a reload doesn't lose it; task 1 will make scenario/plan state server-side.
type Saved = { source: DemoSource | null; caseData: CaseDataset | null; planId?: string; approvedPlanId?: string }
const WS_KEY = 'okno.workspace'
const loadWs = (): Saved => { try { return JSON.parse(localStorage.getItem(WS_KEY) ?? '') } catch { return { source: null, caseData: null } } }
const saveWs = (v: Saved) => { try { localStorage.setItem(WS_KEY, JSON.stringify(v)) } catch { /* storage blocked */ } }

export default function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, retry: false })

  if (me.isPending) return <main><p className="muted">Загрузка…</p></main>
  if (me.error instanceof Unauthorized) return <Login />
  if (me.isError) return <main><p className="error">Ошибка: {me.error.message}</p></main>
  if (new URLSearchParams(window.location.search).get('calendarDemo') === '1') return <main>
    <h1>ОКНО ВСМ <span>/ Ручной демонстрационный пример</span></h1>
    <p className="muted"><a href="/">← Вернуться к парку</a></p>
    <CalendarDemo />
  </main>
  return <Workspace username={me.data.username} role={me.data.role} />
}

const ROLE_NAME: Record<Role, string> = { PLANNER: 'планировщик', TECHNOLOGIST: 'технолог', DISPATCHER: 'диспетчер' }

function Shell({ username, role, tab, onTab, children }: { username: string; role: Role; tab: Tab; onTab: (t: Tab) => void; children: ReactNode }) {
  const qc = useQueryClient()
  // resetQueries drops cached data (trains of the previous user) and re-runs
  // /auth/me, which now 401s and sends us back to the login screen.
  const logout = useMutation({ mutationFn: api.logout, onSuccess: () => qc.resetQueries() })
  return (
    <>
      <header className="top">
        <div>
          <Logo />
          <strong>ОКНО ВСМ</strong>
          <span>Планирование ТО парка ЭВС360</span>
        </div>
        <span>
          {username} ({ROLE_NAME[role] ?? role}) ·{' '}
          <button className="link" onClick={() => logout.mutate()} disabled={logout.isPending}>
            Выйти
          </button>
        </span>
      </header>
      <nav className="tabs" aria-label="Рабочие области">
        {TABS[role].map((t) => (
          <button key={t} className={t === tab ? 'on' : ''} aria-current={t === tab ? 'page' : undefined} onClick={() => onTab(t)}>{TAB_NAME[t]}</button>
        ))}
      </nav>
      <main>
        <span className="demo-label">Демо-данные</span>
        {children}
      </main>
    </>
  )
}

function Workspace({ username, role }: { username: string; role: Role }) {
  const [tab, setTab] = useTab(role)
  const [ws, setWs] = useState<Saved>(loadWs)
  useEffect(() => saveWs(ws), [ws])
  const trains = useQuery<Train[]>({ queryKey: ['trains', ws.source?.scenarioId], queryFn: () => api.getTrains(ws.source!.scenarioId),
    enabled: !!ws.source && role !== 'DISPATCHER', retry: false })
  return (
    <Shell username={username} role={role} tab={tab} onTab={setTab}>
      {tab === 'planning' && <PlanningTab ws={ws} setWs={setWs} canApprove={role === 'PLANNER'} trains={trains.data} />}
      {tab === 'fleet' && <Fleet ownPlanId={ws.planId} approvedHere={ws.approvedPlanId} trains={trains.data} detailed={role !== 'DISPATCHER'} />}
      {tab === 'events' && <Events username={username} ownPlanId={ws.planId} approvedHere={ws.approvedPlanId} />}
    </Shell>
  )
}

function PlanningTab({ ws, setWs, canApprove, trains }: { ws: Saved; setWs: Dispatch<SetStateAction<Saved>>; canApprove: boolean; trains?: Train[] }) {
  const qc = useQueryClient()
  const source = ws.source
  const caseData = ws.caseData
  const setSource = (s: DemoSource) => setWs((w) => ({ ...w, source: s, planId: undefined }))
  const [arrivalMinute, setArrivalMinute] = useState(50)
  const [dataset, setDataset] = useState<'TOY' | CaseDataset['dataset']>('FULL43')
  const shortDemo = !!source && !caseData
  const planId = ws.planId
  const setPlanId = (id: string | undefined) => { if (id) setWs((w) => (id === w.planId ? w : { ...w, planId: id })) }
  const [recovery, setRecovery] = useState<RecoveryBoard | null>(null)
  const [resumeId] = useState(() => new URLSearchParams(window.location.search).get('operations'))
  const scenarioId = source?.scenarioId ?? null
  const resumed = useQuery({queryKey:['operations',resumeId],queryFn:()=>api.getRecovery(resumeId!),enabled:!!resumeId && !source,retry:false})
  useEffect(() => {
    if(resumed.data && !source) {
      const board=resumed.data
      setRecovery(board)
      setWs((w) => ({ ...w, source: {scenarioId:board.scenarioId,snapshotId:board.sourceSnapshotId,snapshotHash:board.snapshotHash,provenance:board.provenance} }))
    }
  },[resumed.data,source])

  const importMutation = useMutation({
    mutationFn: async () => {
      if (dataset === 'TOY') return { source: await api.importDemoSource(), details: null, recovery: null }
      const details = await api.importCaseDataset(dataset)
      return { source: details.source, details, recovery: dataset === 'FULL43' ? await api.createRecovery(details.source.scenarioId) : null }
    },
    onSuccess: ({ source: loaded, details, recovery: board }) => {
      setWs({ source: loaded, caseData: details, planId: undefined }); setArrivalMinute(50)
      setRecovery(board)
      window.history.replaceState(null,'',(board ? `/?operations=${board.id}` : '/') + window.location.hash)
      void qc.invalidateQueries({ queryKey: ['scenario', loaded.scenarioId] })
      void qc.invalidateQueries({ queryKey: ['trains', loaded.scenarioId] })
    }
  })

  const changeTrip = useMutation({
    mutationFn: () => api.changeR1Arrival(scenarioId!, arrivalMinute),
    onSuccess: setSource
  })

  // The planner works on the latest calculation; the approved one is the before/after base.
  const view = usePlanView(undefined, ws.approvedPlanId)

  return (
    <>
        <section className="card pad">
          <h2><Icon name="database" />Исходные данные</h2>
          <div className="row">
            <label>Набор
              <select value={dataset} onChange={(e) => setDataset(e.target.value as typeof dataset)}>
                <option value="E2_6">6 составов · 252 рейса</option>
                <option value="BLOCKED6">6 составов · путь недоступен после первых суток</option>
                <option value="FULL43">43 состава · отказ перед рейсом, замена и резерв</option>
                <option value="TOY">1 состав · изменение рейса R1</option>
              </select>
            </label>
            <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
              {importMutation.isPending ? 'Загрузка…' : 'Загрузить'}
            </button>
            {source && <span className="muted" title={source.snapshotHash}>Версия данных {source.snapshotHash.slice(0, 12)}</span>}
          </div>
          {caseData && <p><strong>{caseData.trainCount} составов · {caseData.tripCount} рейсов · 14 суток</strong></p>}
          {importMutation.isError && <p className="error">Ошибка: {importMutation.error.message}</p>}
        </section>
        {resumed.isFetching && !source && <p className="muted">Восстанавливаем оперативный сценарий из PostgreSQL…</p>}
        {resumed.isError && !source && <p className="error">Не удалось восстановить сценарий: {resumed.error.message}</p>}
        {recovery && <RecoveryConsole key={recovery.id} board={recovery} onChange={setRecovery} />}

        <InboxSection calendar={view.calendar} activePlanId={view.activePlanId} />

        {source && shortDemo && (
          <section className="card pad">
            <h2><Icon name="route" />Рейс R1</h2>
            <div className="row">
              <label>Прибытие (МСК)
                <select value={arrivalMinute} onChange={(e) => setArrivalMinute(Number(e.target.value))}>
                  {[50, 55, 60].map((minute) => <option key={minute} value={minute}>01.07.2028 {minute === 60 ? '01:00' : `00:${minute}`}</option>)}
                </select>
              </label>
              <button onClick={() => changeTrip.mutate()} disabled={changeTrip.isPending}>
                {changeTrip.isPending ? 'Сохраняем…' : 'Сохранить'}
              </button>
            </div>
            {changeTrip.isError && <p className="error">{changeTrip.error.message}</p>}
          </section>
        )}
        {source && !recovery && (caseData?.planningSupported !== false
          ? <Planning key={source.snapshotHash} scenarioId={source.scenarioId} trains={trains}
              canApprove={canApprove} onPlan={setPlanId} initialPlanId={planId}
              onApproved={(id) => setWs((w) => ({ ...w, planId: id, approvedPlanId: id }))} />
          : <p className="muted">Расчёт для полного парка появится после подключения резерва, уборки и закреплённых работ. Для расчёта выберите набор из 6 составов.</p>)}
        {planId && view.activePlanId && planId !== view.activePlanId && (
          <section className="card pad">
            <h2><Icon name="plan" />Сравнение с действующим планом</h2>
            <PlanCompare beforeId={view.activePlanId} afterId={planId} />
          </section>
        )}
    </>
  )
}
