import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, DemoSource, ru, Train, Unauthorized } from './api'
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
  const scenarioId = source?.scenarioId ?? null

  const importMutation = useMutation({
    mutationFn: api.importDemoSource,
    onSuccess: (data) => { setSource(data); setArrivalMinute(50) }
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

        {!source && (
          <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
            {importMutation.isPending ? 'Загрузка…' : 'Загрузить демо-данные'}
          </button>
        )}
        {importMutation.isError && <p className="error">Ошибка: {importMutation.error.message}</p>}
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

        {source && (
          <>
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
                <span className="muted" title={source.snapshotHash}>Версия данных {source.snapshotHash.slice(0, 12)}</span>
              </div>
              {changeTrip.isError && <p className="error">{changeTrip.error.message}</p>}
            </section>
            <Planning key={source.snapshotHash} scenarioId={source.scenarioId} trains={trainsQuery.data} />
          </>
        )}
      </main>
    </>
  )
}
