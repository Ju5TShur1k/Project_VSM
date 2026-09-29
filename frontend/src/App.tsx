import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CaseDataset, DemoSource, RecoveryBoard, Role, ru, Train, Unauthorized } from './api'
import type { CalendarData } from './calendar/PlanningCalendar'
import Dispatcher, { IncidentLog } from './Dispatcher'
import RecoveryConsole from './RecoveryConsole'
import { Icon, Logo } from './Icons'
import Login from './Login'
import Planning from './Planning'
import CalendarDemo from './calendar/CalendarDemo'

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
  const { username, role } = me.data
  return (
    <Shell username={username} role={role}>
      {role === 'DISPATCHER' ? <Dispatcher /> : <Fleet canApprove={role === 'PLANNER'} />}
    </Shell>
  )
}

const ROLE_NAME: Record<Role, string> = { PLANNER: 'планировщик', TECHNOLOGIST: 'технолог', DISPATCHER: 'диспетчер' }

function Shell({ username, role, children }: { username: string; role: Role; children: ReactNode }) {
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
      <main>
        <span className="demo-label">Демо-данные</span>
        {children}
      </main>
    </>
  )
}

const time = (iso: string) =>
  new Date(iso).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

// ponytail: distance parsed from the trip label ("Рейс R1 · 670 км"); add distanceKm
// to CalendarEvent if labels ever change.
const km = (label: string) => Number(label.match(/(\d+)\s*км/)?.[1] ?? 0)

// Everything here comes from the plan's calendar, i.e. the same snapshot the solver used.
function TrainCard({ train, calendar, onClose }: { train: Train; calendar: CalendarData | undefined; onClose: () => void }) {
  const events = calendar?.events.filter((e) => e.trainId === train.id) ?? []
  const trips = events.filter((e) => e.kind === 'TRIP')
  const services = events.filter((e) => e.kind === 'SERVICE').sort((a, b) => a.startAt.localeCompare(b.startAt))
  const next = services[0]
  const tripsBefore = next ? trips.filter((t) => Date.parse(t.endAt) <= Date.parse(next.startAt)) : trips
  const arriveKm = train.mileageKm + tripsBefore.reduce((sum, t) => sum + km(t.label), 0)
  const n = (v: number) => v.toLocaleString('ru-RU')
  return (
    <section className="card pad">
      <div className="bar">
        <h2><Icon name="train" />Состав {train.externalId}</h2>
        <button className="btn-outline" onClick={onClose}>Закрыть</button>
      </div>
      <dl className="kv">
        <dt>Статус</dt><dd><span className={`badge ${train.status}`}>{train.status === 'FAILED' ? 'Неисправен' : ru(train.status)}</span></dd>
        <dt>Пробег сейчас</dt><dd>{n(train.mileageKm)} км</dd>
        <dt>Рейсов в горизонте</dt>
        <dd>{calendar ? `${trips.length} · ${n(trips.reduce((s, t) => s + km(t.label), 0))} км` : 'появится после расчёта'}</dd>
        {next && <>
          <dt>Ближайшее ТО</dt><dd>{next.cycleCode ?? next.label} · {time(next.startAt)} – {time(next.endAt)}</dd>
          {next.releaseOdometerKm != null && next.dueOdometerKm != null &&
            <><dt>Окно по пробегу</dt><dd>{n(next.releaseOdometerKm)} – {n(next.dueOdometerKm)} км</dd></>}
          <dt>Рейсов до ТО</dt><dd>{tripsBefore.length}</dd>
          <dt>Пробег к началу ТО</dt><dd>≈ {n(arriveKm)} км</dd>
        </>}
        {calendar && !next && <><dt>ТО в горизонте</dt><dd>не требуется</dd></>}
      </dl>
      {services.length > 1 && (
        <details>
          <summary>Все ТО в плане ({services.length})</summary>
          <ul className="viol">
            {services.map((s) => <li key={s.id}>{s.cycleCode ?? s.label}: {time(s.startAt)} – {time(s.endAt)}</li>)}
          </ul>
        </details>
      )}
    </section>
  )
}


function Fleet({ canApprove }: { canApprove: boolean }) {
  const qc = useQueryClient()
  const [source, setSource] = useState<DemoSource | null>(null)
  const [arrivalMinute, setArrivalMinute] = useState(50)
  const [dataset, setDataset] = useState<'TOY' | CaseDataset['dataset']>('FULL43')
  const [caseData, setCaseData] = useState<CaseDataset | null>(null)
  const [shortDemo, setShortDemo] = useState(false)
  const [planId, setPlanId] = useState<string | undefined>()
  const [cardId, setCardId] = useState<string | null>(null)
  const [recovery, setRecovery] = useState<RecoveryBoard | null>(null)
  const [resumeId] = useState(() => new URLSearchParams(window.location.search).get('operations'))
  const scenarioId = source?.scenarioId ?? null
  const resumed = useQuery({queryKey:['operations',resumeId],queryFn:()=>api.getRecovery(resumeId!),enabled:!!resumeId && !source,retry:false})
  useEffect(() => {
    if(resumed.data && !source) {
      const board=resumed.data
      setRecovery(board)
      setSource({scenarioId:board.scenarioId,snapshotId:board.sourceSnapshotId,snapshotHash:board.snapshotHash,provenance:board.provenance})
    }
  },[resumed.data,source])

  const importMutation = useMutation({
    mutationFn: async () => {
      if (dataset === 'TOY') return { source: await api.importDemoSource(), details: null, recovery: null }
      const details = await api.importCaseDataset(dataset)
      return { source: details.source, details, recovery: dataset === 'FULL43' ? await api.createRecovery(details.source.scenarioId) : null }
    },
    onSuccess: ({ source: loaded, details, recovery: board }) => {
      setSource(loaded); setCaseData(details); setShortDemo(details === null); setArrivalMinute(50)
      setRecovery(board); setPlanId(undefined); setCardId(null)
      window.history.replaceState(null,'',board ? `/?operations=${board.id}` : '/')
      void qc.invalidateQueries({ queryKey: ['scenario', loaded.scenarioId] })
      void qc.invalidateQueries({ queryKey: ['trains', loaded.scenarioId] })
    }
  })

  const changeTrip = useMutation({
    mutationFn: () => api.changeR1Arrival(scenarioId!, arrivalMinute),
    onSuccess: setSource
  })

  const trainsQuery = useQuery<Train[]>({
    queryKey: ['trains', scenarioId],
    queryFn: () => api.getTrains(scenarioId!),
    enabled: !!scenarioId && !recovery
  })

  // Same query key as in Planning, so the card reuses the loaded calendar.
  const calendar = useQuery({ queryKey: ['calendar', planId], queryFn: () => api.getCalendar(planId!), enabled: !!planId })
  const fleetTrains: Train[] | undefined = recovery
    ? recovery.trains.map(t=>({...t,nextObligation:`Уборка: ${t.tripsSinceCleaning ?? 'неизвестно'} / 4 рейса`}))
    : trainsQuery.data
  const card = fleetTrains?.find(t=>t.id===cardId)

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
        {trainsQuery.isError && <p className="error">Ошибка: {trainsQuery.error.message}</p>}
        {resumed.isFetching && !source && <p className="muted">Восстанавливаем оперативный сценарий из PostgreSQL…</p>}
        {resumed.isError && !source && <p className="error">Не удалось восстановить сценарий: {resumed.error.message}</p>}
        {recovery && <RecoveryConsole key={recovery.id} board={recovery} onChange={setRecovery} />}

        {fleetTrains && fleetTrains.length > 0 && (
          <section className="card">
            <h2 className="pad-h"><Icon name="train" />Парк</h2>
            <table>
              <thead>
                <tr>
                  <th>Состав</th>
                  <th>Статус</th>
                  <th className="num">Пробег, км</th>
                  <th>{recovery ? 'Уборка' : 'Ближайшее ТО'}</th>
                  {recovery && <th>Местонахождение</th>}
                </tr>
              </thead>
              <tbody>
                {fleetTrains.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <button className="link" onClick={() => setCardId(t.id)} title="Карточка поезда">{t.externalId}</button>
                    </td>
                    <td>
                      <span className={`badge ${t.status}`}>{t.status === 'FAILED' ? 'Неисправен' : ru(t.status)}</span>
                    </td>
                    <td className="num">{t.mileageKm.toLocaleString('ru-RU')}</td>
                    <td>{t.nextObligation}</td>
                    {recovery && <td>{recovery.trains.find(train=>train.id===t.id)?.location.replace(/SPB_DEPOT/g,'Санкт-Петербург').replace(/MOSCOW/g,'Москва')}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        {card && <TrainCard train={card} calendar={calendar.data} onClose={() => setCardId(null)} />}

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
          ? <Planning key={source.snapshotHash} scenarioId={source.scenarioId} trains={trainsQuery.data}
              canApprove={canApprove} onPlan={setPlanId} />
          : <p className="muted">Расчёт для полного парка появится после подключения резерва, уборки и закреплённых работ. Для расчёта выберите набор из 6 составов.</p>)}
        <section className="card">
          <h2 className="pad-h"><Icon name="bell" />Сообщения диспетчера</h2>
          <IncidentLog />
        </section>
    </>
  )
}
