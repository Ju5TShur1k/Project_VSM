import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CaseDataset, DemoSource, RecoveryBoard, Train, Unauthorized } from './api'
import Login from './Login'
import Planning from './Planning'
import CalendarDemo from './calendar/CalendarDemo'
import RecoveryConsole from './RecoveryConsole'

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
  return <Fleet username={me.data.username} />
}

function Fleet({ username }: { username: string }) {
  const qc = useQueryClient()
  const [source, setSource] = useState<DemoSource | null>(null)
  const [arrivalMinute, setArrivalMinute] = useState(50)
  const [dataset, setDataset] = useState<'TOY' | CaseDataset['dataset']>('FULL43')
  const [caseData, setCaseData] = useState<CaseDataset | null>(null)
  const [recovery, setRecovery] = useState<RecoveryBoard | null>(null)
  const [resumeId] = useState(() => new URLSearchParams(window.location.search).get('operations'))
  const [shortDemo, setShortDemo] = useState(false)
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
      const recovery = dataset === 'FULL43' ? await api.createRecovery(details.source.scenarioId) : null
      return { source: details.source, details, recovery }
    },
    onSuccess: ({ source: loaded, details, recovery: board }) => {
      setSource(loaded); setCaseData(details); setShortDemo(details === null); setArrivalMinute(50)
      setRecovery(board)
      window.history.replaceState(null,'',board ? `/?operations=${board.id}` : '/')
      void qc.invalidateQueries({ queryKey: ['scenario', loaded.scenarioId] })
      void qc.invalidateQueries({ queryKey: ['trains', loaded.scenarioId] })
    }
  })

  const changeTrip = useMutation({
    mutationFn: () => api.changeR1Arrival(scenarioId!, arrivalMinute),
    onSuccess: setSource
  })

  // resetQueries drops cached data (trains of the previous user) and re-runs
  // /auth/me, which now 401s and sends us back to the login screen.
  const logout = useMutation({ mutationFn: api.logout, onSuccess: () => qc.resetQueries() })

  const scenario = useQuery({
    queryKey: ['scenario', scenarioId],
    queryFn: () => api.getScenario(scenarioId!),
    enabled: !!scenarioId && !recovery
  })

  const trainsQuery = useQuery<Train[]>({
    queryKey: ['trains', scenarioId],
    queryFn: () => api.getTrains(scenarioId!),
    enabled: !!scenarioId && !recovery
  })

  return (
    <main>
      <div className="bar">
        <h1>
          ОКНО ВСМ <span>/ Парк</span>
        </h1>
        <span className="muted">
          {username} ·{' '}
          <button className="link" onClick={() => logout.mutate()} disabled={logout.isPending}>
            Выйти
          </button>
        </span>
      </div>

      <p className="demo-label">Демонстрационные данные</p>
      <p className="muted">Исходные факты сохраняются в PostgreSQL. Каждый расчёт получает неизменяемый snapshot; рейсы остаются фиксированными.</p>
      <section className="card pad">
        <h2>Набор исходных данных</h2>
        <div className="row">
          <label>Сценарий
            <select value={dataset} onChange={(e) => setDataset(e.target.value as typeof dataset)}>
              <option value="E2_6">6 составов · 252 рейса · нормативы IS100/IS200 из кейса</option>
              <option value="BLOCKED6">6 составов · путь недоступен после первых суток</option>
              <option value="FULL43">43 состава · отказ перед рейсом, замена и резерв</option>
              <option value="TOY">Короткий пример · один состав и изменение R1</option>
            </select>
          </label>
          <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
            {importMutation.isPending ? 'Загрузка…' : 'Загрузить выбранный набор'}
          </button>
        </div>
        <p className="muted">Пробеги, история и расписание модельные. Нормативные длительности и расстояние 670 км взяты из кейса.</p>
      </section>

      {source ? (
        <>
          <p className="muted">
            Сценарий <code>{scenarioId}</code>
            {scenario.data && <> · {scenario.data.provenance}</>}
          </p>
          <p className="muted">Сохранённый snapshot <code>{source.snapshotId}</code> · hash <code>{source.snapshotHash}</code></p>
          {caseData && <section className="card pad">
            <p><strong>{caseData.trainCount} составов · {caseData.tripCount} рейсов · горизонт 14 суток</strong></p>
            {caseData.warnings.map((warning) => <p className="muted" key={warning}>{warning}</p>)}
          </section>}
          {shortDemo && <>
          <section className="card pad">
            <h2>Исходные данные · рейс R1</h2>
            <p className="muted">Прибытие R1 влияет на доступное время обслуживания перед рейсом R2. Изменение создаёт новый snapshot; предыдущий остаётся в базе.</p>
            <div className="row">
              <label>Прибытие R1, Москва
                <select value={arrivalMinute} onChange={(e) => setArrivalMinute(Number(e.target.value))}>
                  {[50, 55, 60].map((minute) => <option key={minute} value={minute}>01.07.2028 {minute === 60 ? '01:00' : `00:${minute}`}</option>)}
                </select>
              </label>
              <button onClick={() => changeTrip.mutate()} disabled={changeTrip.isPending}>
                {changeTrip.isPending ? 'Сохраняем…' : 'Сохранить новый snapshot'}
              </button>
            </div>
            {changeTrip.isError && <p className="error">{changeTrip.error.message}</p>}
          </section>
          </>}
          {recovery ? <RecoveryConsole key={recovery.id} board={recovery} onChange={setRecovery} /> : caseData?.planningSupported !== false
            ? <Planning key={source.snapshotHash} scenarioId={source.scenarioId} trains={trainsQuery.data} />
            : <p className="muted">Полный парк сохранён для интеграции E3. Расчёт станет доступен после подключения резерва, уборки и закреплённых работ; для проверки календаря выберите набор из 6 составов.</p>}
        </>
      ) : null}

      {importMutation.isError && <p className="error">Ошибка: {importMutation.error.message}</p>}
      {resumed.isFetching && !source && <p className="muted">Восстанавливаем оперативный сценарий из PostgreSQL…</p>}
      {resumed.isError && !source && <p className="error">Не удалось восстановить сценарий: {resumed.error.message}</p>}
      {trainsQuery.isLoading && <p className="muted">Загрузка парка…</p>}
      {trainsQuery.isError && <p className="error">Ошибка: {trainsQuery.error.message}</p>}
      {trainsQuery.data && trainsQuery.data.length === 0 && <p className="muted">Парк пуст.</p>}

      {((recovery?.trains.length ?? 0)>0 || (trainsQuery.data?.length ?? 0)>0) && (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Статус</th>
                <th className="num">Пробег, км</th>
                <th>Ближайшее обязательство</th>
                {recovery && <th>Местонахождение</th>}
              </tr>
            </thead>
            <tbody>
              {(recovery ? recovery.trains.map(t=>({...t,nextObligation:`Уборка: ${t.tripsSinceCleaning ?? 'неизвестно'} / 4 рейса`})) : trainsQuery.data ?? []).map((t) => (
                <tr key={t.id}>
                  <td>{t.externalId}</td>
                  <td>
                    <span className={`badge ${t.status}`}>{{LINE:'На линии',RESERVE:'Горячий резерв',FAILED:'Отказ · выпуск запрещён',MAINTENANCE:'Плановое ТО'}[t.status] ?? t.status}</span>
                  </td>
                  <td className="num">{t.mileageKm}</td>
                  <td>{t.nextObligation}</td>
                  {recovery && <td>{recovery.trains.find(train=>train.id===t.id)?.location.replace(/SPB_DEPOT/g,'Санкт-Петербург').replace(/MOSCOW/g,'Москва')}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  )
}
