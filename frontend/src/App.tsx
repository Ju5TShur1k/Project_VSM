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
    <div style={{ fontFamily: 'sans-serif', padding: 24 }}>
      <h1>ОКНО ВСМ — Парк</h1>

      {!scenarioId && (
        <button onClick={() => importMutation.mutate()} disabled={importMutation.isPending}>
          {importMutation.isPending ? 'Импорт…' : 'Импортировать демо-сценарий (43 поезда)'}
        </button>
      )}

      {scenarioId && <p>Сценарий: {scenarioId}</p>}

      {trainsQuery.isLoading && <p>Загрузка парка…</p>}
      {trainsQuery.isError && <p>Ошибка: {(trainsQuery.error as Error).message}</p>}
      {trainsQuery.data && trainsQuery.data.length === 0 && <p>Парк пуст.</p>}

      {trainsQuery.data && trainsQuery.data.length > 0 && (
        <table border={1} cellPadding={6}>
          <thead>
            <tr>
              <th>ID</th>
              <th>Статус</th>
              <th>Пробег, км</th>
              <th>Ближайшее обязательство</th>
            </tr>
          </thead>
          <tbody>
            {trainsQuery.data.map((t) => (
              <tr key={t.id}>
                <td>{t.externalId}</td>
                <td>{t.status}</td>
                <td>{t.mileageKm}</td>
                <td>{t.nextObligation}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
