import { useEffect, useState } from 'react'
import type { Dispatch, ReactNode, SetStateAction } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, FullDraft, Proposal, Role, Unauthorized, Version } from './api'
import type { CalendarData } from './calendar/PlanningCalendar'
import { Logo } from './Icons'
import Login from './Login'
import PlanTab from './PlanTab'
import Proposals from './Proposals'
import Fleet from './Fleet'

type Tab = 'plan' | 'requests' | 'fleet'
const TABS: Partial<Record<Role, Tab[]>> = { PLANNER: ['plan', 'requests', 'fleet'], DISPATCHER: ['requests', 'fleet'] }
const TAB_NAME: Record<Tab, string> = { plan: 'План ТО', requests: 'Заявки', fleet: 'Парк' }
const ROLE_NAME: Partial<Record<Role, string>> = { PLANNER: 'планировщик', DISPATCHER: 'диспетчер' }

// Working set kept in the browser: which fleet is open, today's operations board, the
// last built plan and its build statistics (the server keeps the plan, not the statistics).
export type Ws = { rootId?: string; operationsId?: string; planId?: string; previousPlanId?: string; draft?: FullDraft }
export type SetWs = Dispatch<SetStateAction<Ws>>
export type Ctx = {
  ws: Ws; setWs: SetWs; rootId?: string; operationsId?: string; head?: Version; planId?: string
  calendar?: CalendarData; proposals: Proposal[]
}
const WS_KEY = 'okno.ws'
const loadWs = (): Ws => { try { return JSON.parse(localStorage.getItem(WS_KEY) ?? '{}') } catch { return {} } }

// The open tab lives in the URL hash, so a reload or a shared link lands on it.
function useTab(tabs: Tab[]): [Tab, (t: Tab) => void] {
  const pick = () => { const h = window.location.hash.slice(1) as Tab; return tabs.includes(h) ? h : tabs[0] }
  const [tab, setTab] = useState(pick)
  useEffect(() => { const on = () => setTab(pick()); window.addEventListener('hashchange', on); return () => window.removeEventListener('hashchange', on) })
  return [tab, (t) => { window.location.hash = t }]
}

export default function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, retry: false })
  if (me.isPending) return <main><p className="muted">Загрузка…</p></main>
  if (me.error instanceof Unauthorized) return <Login />
  if (me.isError) return <main><p className="error">Ошибка: {me.error.message}</p></main>
  const tabs = TABS[me.data.role]
  if (!tabs) return <Shell username={me.data.username} role={me.data.role}><p className="banner">Эта роль не используется в текущей версии. Войдите как планировщик или диспетчер.</p></Shell>
  return <Workspace username={me.data.username} role={me.data.role} tabs={tabs} />
}

function Shell({ username, role, tabs, tab, onTab, children }: {
  username: string; role: Role; tabs?: Tab[]; tab?: Tab; onTab?: (t: Tab) => void; children: ReactNode
}) {
  const qc = useQueryClient()
  // resetQueries drops the previous user's data and re-runs /auth/me, which now 401s.
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
          <button className="link" onClick={() => logout.mutate()} disabled={logout.isPending}>Выйти</button>
        </span>
      </header>
      {tabs && (
        <nav className="tabs" aria-label="Рабочие области">
          {tabs.map((t) => (
            <button key={t} className={t === tab ? 'on' : ''} aria-current={t === tab ? 'page' : undefined} onClick={() => onTab?.(t)}>{TAB_NAME[t]}</button>
          ))}
        </nav>
      )}
      <main>
        <span className="demo-label">Модельные данные</span>
        {children}
      </main>
    </>
  )
}

function Workspace({ username, role, tabs }: { username: string; role: Role; tabs: Tab[] }) {
  const [tab, setTab] = useTab(tabs)
  const [ws, setWs] = useState<Ws>(loadWs)
  useEffect(() => { try { localStorage.setItem(WS_KEY, JSON.stringify(ws)) } catch { /* storage blocked */ } }, [ws])

  // A dispatcher on another computer learns the open fleet from the requests it receives.
  const proposals = useQuery({ queryKey: ['proposals', ws.rootId], queryFn: () => api.proposals(ws.rootId), refetchInterval: 5_000 })
  const rootId = ws.rootId ?? proposals.data?.[0]?.scenarioId
  const operationsId = ws.operationsId ?? proposals.data?.map((p) => p.payload.kind === 'TRAIN_FAILURE' ? p.payload.operationsId : undefined).find(Boolean)
  const head = useQuery({ queryKey: ['head', rootId], queryFn: () => api.head(rootId!), enabled: !!rootId, refetchInterval: 10_000 })
  const last = useQuery({ queryKey: ['selection', rootId], queryFn: () => api.lastPlan(rootId!), enabled: !!rootId, refetchInterval: 15_000 })
  const planId = last.data?.planId ?? ws.planId
  // Failures are decided on today's operations board; the planner opens one for the current data.
  useEffect(() => {
    if (role !== 'PLANNER' || !head.data || ws.operationsId) return
    api.openBoard(head.data.scenarioId).then((b) => setWs((w) => ({ ...w, operationsId: b.id }))).catch(() => { /* retried on next data change */ })
  }, [role, head.data, ws.operationsId])
  const calendar = useQuery({ queryKey: ['calendar', planId], queryFn: () => api.calendar(planId!), enabled: !!planId, staleTime: Infinity })
  const ctx = { ws, setWs, rootId, operationsId, head: head.data, planId, calendar: calendar.data, proposals: proposals.data ?? [] }

  return (
    <Shell username={username} role={role} tabs={tabs} tab={tab} onTab={setTab}>
      {tab === 'plan' && <PlanTab {...ctx} onRequests={() => setTab('requests')} />}
      {tab === 'requests' && <Proposals {...ctx} role={role} onPlan={() => setTab('plan')} />}
      {tab === 'fleet' && <Fleet calendar={calendar.data} loading={calendar.isFetching} detailed={role === 'PLANNER'} />}
    </Shell>
  )
}
