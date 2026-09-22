export type Train = {
  id: string
  externalId: string
  status: string
  mileageKm: number
  nextObligation: string
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  return res.json()
}

export const api = {
  importScenario: () =>
    fetch('/api/v1/scenarios/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
    }).then(json<{ scenarioId: string; warnings: string[]; provenance: string }>),

  getTrains: (scenarioId: string) =>
    fetch(`/api/v1/scenarios/${scenarioId}/trains`).then(json<Train[]>)
}
