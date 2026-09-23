import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api, Train } from './api'

export default function App() {
  const [scenarioId, setScenarioId] = useState<string | null>(null)

  const importMutation = useMutation({
    mutationFn: api.importScenario,
    onSuccess: (data) => setScenarioId(data.scenarioId)
  })

  const trainsQuery = useQuery<Train[]>({
    queryKey: ['trains', scenarioId],
    queryFn: () => api.getTrains(scenarioId!),
    enabled: !!scenarioId
  })

  return (
    <main>
      <h1>
        ОКНО ВСМ <span>/ Парк</span>
      </h1>

      {scenarioId ? (
        <p className="muted">
          Сценарий <code>{scenarioId}</code>
        </p>
      ) : (
        <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
          {importMutation.isPending ? 'Импорт…' : 'Импортировать демо-сценарий (43 поезда)'}
        </button>
      )}

      {importMutation.isError && <p className="error">Ошибка: {(importMutation.error as Error).message}</p>}
      {trainsQuery.isLoading && <p className="muted">Загрузка парка…</p>}
      {trainsQuery.isError && <p className="error">Ошибка: {(trainsQuery.error as Error).message}</p>}
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
