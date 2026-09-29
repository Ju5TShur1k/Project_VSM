import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CaseDataset, DemoSource, ru, Train, Unauthorized } from './api'
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
  return <Fleet username={me.data.username} />
}

function Fleet({ username }: { username: string }) {
  const qc = useQueryClient()
  const [source, setSource] = useState<DemoSource | null>(null)
  const [arrivalMinute, setArrivalMinute] = useState(50)
  const [dataset, setDataset] = useState<'TOY' | CaseDataset['dataset']>('E2_6')
  const [caseData, setCaseData] = useState<CaseDataset | null>(null)
  const [shortDemo, setShortDemo] = useState(false)
  const scenarioId = source?.scenarioId ?? null

  const importMutation = useMutation({
    mutationFn: async () => {
      if (dataset === 'TOY') return { source: await api.importDemoSource(), details: null }
      const details = await api.importCaseDataset(dataset)
      return { source: details.source, details }
    },
    onSuccess: ({ source: loaded, details }) => {
      setSource(loaded); setCaseData(details); setShortDemo(details === null); setArrivalMinute(50)
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

  const trainsQuery = useQuery<Train[]>({
    queryKey: ['trains', scenarioId],
    queryFn: () => api.getTrains(scenarioId!),
    enabled: !!scenarioId
  })

  return (
    <>
      <header className="top">
        <div>
          <strong>ОКНО ВСМ</strong>
          <span>Планирование ТО парка ЭВС360</span>
        </div>
        <span>
          {username} ·{' '}
          <button className="link" onClick={() => logout.mutate()} disabled={logout.isPending}>
            Выйти
          </button>
        </span>
      </header>
      <main>
        <span className="demo-label">Демо-данные</span>

        <section className="card pad">
          <h2>Исходные данные</h2>
          <div className="row">
            <label>Набор
              <select value={dataset} onChange={(e) => setDataset(e.target.value as typeof dataset)}>
                <option value="E2_6">6 составов · 252 рейса</option>
                <option value="BLOCKED6">6 составов · путь недоступен после первых суток</option>
                <option value="FULL43">43 состава · полный парк</option>
                <option value="TOY">1 состав · изменение рейса R1</option>
              </select>
            </label>
            <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
              {importMutation.isPending ? 'Загрузка…' : 'Загрузить'}
            </button>
            {source && <span className="muted" title={source.snapshotHash}>Версия данных {source.snapshotHash.slice(0, 12)}</span>}
          </div>
          {caseData && <p><strong>{caseData.trainCount} составов · {caseData.tripCount} рейсов · 14 суток</strong></p>}
          {caseData?.warnings.map((warning) => <p className="muted" key={warning}>{warning}</p>)}
          {importMutation.isError && <p className="error">Ошибка: {importMutation.error.message}</p>}
        </section>
        {trainsQuery.isError && <p className="error">Ошибка: {trainsQuery.error.message}</p>}

        {trainsQuery.data && trainsQuery.data.length > 0 && (
          <section className="card">
            <h2 className="pad-h">Парк</h2>
            <table>
              <thead>
                <tr>
                  <th>Состав</th>
                  <th>Статус</th>
                  <th className="num">Пробег, км</th>
                  <th>Ближайшее ТО</th>
                </tr>
              </thead>
              <tbody>
                {trainsQuery.data.map((t) => (
                  <tr key={t.id}>
                    <td>{t.externalId}</td>
                    <td>
                      <span className={`badge ${t.status}`}>{ru(t.status)}</span>
                    </td>
                    <td className="num">{t.mileageKm.toLocaleString('ru-RU')}</td>
                    <td>{t.nextObligation}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        {source && shortDemo && (
          <section className="card pad">
            <h2>Рейс R1</h2>
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
        {source && (caseData?.planningSupported !== false
          ? <Planning key={source.snapshotHash} scenarioId={source.scenarioId} trains={trainsQuery.data} />
          : <p className="muted">Расчёт для полного парка появится после подключения резерва, уборки и закреплённых работ. Для расчёта выберите набор из 6 составов.</p>)}
      </main>
    </>
  )
}
