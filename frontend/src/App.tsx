import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, Train, Unauthorized } from './api'
import Login from './Login'
import Planning from './Planning'

export default function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, retry: false })

  if (me.isPending) return <main><p className="muted">Загрузка…</p></main>
  if (me.error instanceof Unauthorized) return <Login />
  if (me.isError) return <main><p className="error">Ошибка: {me.error.message}</p></main>
  return <Fleet username={me.data.username} />
}

function Fleet({ username }: { username: string }) {
  const qc = useQueryClient()
  const [scenarioId, setScenarioId] = useState<string | null>(null)

  const importMutation = useMutation({
    mutationFn: api.importScenario,
    onSuccess: (data) => setScenarioId(data.scenarioId)
  })

  // resetQueries drops cached data (trains of the previous user) and re-runs
  // /auth/me, which now 401s and sends us back to the login screen.
  const logout = useMutation({ mutationFn: api.logout, onSuccess: () => qc.resetQueries() })

  // provenance shows which failures were injected, e.g. "synthetic+MACHINE_DOWN"
  const scenario = useQuery({
    queryKey: ['scenario', scenarioId],
    queryFn: () => api.getScenario(scenarioId!),
    enabled: !!scenarioId
  })

  const trainsQuery = useQuery<Train[]>({
    queryKey: ['trains', scenarioId],
    queryFn: () => api.getTrains(scenarioId!),
    enabled: !!scenarioId
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

      {scenarioId ? (
        <>
          <p className="muted">
            Сценарий <code>{scenarioId}</code>
            {scenario.data && <> · {scenario.data.provenance}</>}
          </p>
          <Planning key={scenarioId} scenarioId={scenarioId} onScenarioChange={setScenarioId} />
        </>
      ) : (
        <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
          {importMutation.isPending ? 'Импорт…' : 'Импортировать демо-сценарий (43 поезда)'}
        </button>
      )}

      {importMutation.isError && <p className="error">Ошибка: {importMutation.error.message}</p>}
      {trainsQuery.isLoading && <p className="muted">Загрузка парка…</p>}
      {trainsQuery.isError && <p className="error">Ошибка: {trainsQuery.error.message}</p>}
      {trainsQuery.data && trainsQuery.data.length === 0 && <p className="muted">Парк пуст.</p>}

      {trainsQuery.data && trainsQuery.data.length > 0 && (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Статус</th>
                <th className="num">Пробег, км</th>
                <th>Ближайшее обязательство</th>
              </tr>
            </thead>
            <tbody>
              {trainsQuery.data.map((t) => (
                <tr key={t.id}>
                  <td>{t.externalId}</td>
                  <td>
                    <span className={`badge ${t.status}`}>{t.status}</span>
                  </td>
                  <td className="num">{t.mileageKm}</td>
                  <td>{t.nextObligation}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  )
}
