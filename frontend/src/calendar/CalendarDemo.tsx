import PlanningCalendar, { type CalendarData } from './PlanningCalendar'

// A hand-checked E2 fixture. This is intentionally separate from F1's mock plan API.
const data: CalendarData = {
  scenarioId: 'synthetic-e2-fixed-turns',
  snapshotHash: 'synthetic-demo-not-sha256',
  provenance: 'synthetic · ручной пример',
  policy: 'WHOLE_CYCLE_EDD',
  solverStatus: 'FEASIBLE (демонстрационный интервал)',
  independentlyValidated: false,
  horizonStart: '2028-07-01T00:00:00+03:00',
  horizonEnd: '2028-07-01T04:00:00+03:00',
  trains: [{ id: 'EVS-SYN-1', label: 'EVS-SYN-1' }],
  resources: [{ id: 'PATH', label: 'Путь PATH' }],
  events: [
    { id: 'trip-1', kind: 'TRIP', trainId: 'EVS-SYN-1', label: 'Рейс R1 · 670 км',
      startAt: '2028-07-01T00:20:00+03:00', endAt: '2028-07-01T00:50:00+03:00',
      source: 'synthetic fixed turn', reason: 'Заданный рейс; его время не меняется планировщиком.' },
    { id: 'service-1', kind: 'SERVICE', trainId: 'EVS-SYN-1', resourceId: 'PATH', label: 'IS200 · 30 мин',
      cycleCode: 'IS200', covers: ['IS200@25 000', 'IS100@25 000'],
      releaseOdometerKm: 22_500, dueOdometerKm: 27_500,
      dueAt: '2028-07-01T04:00:00+03:00',
      startAt: '2028-07-01T00:50:00+03:00', endAt: '2028-07-01T01:20:00+03:00',
      source: 'CASE интервалы и допуски; synthetic длительность и путь',
      reason: 'Первый свободный 30-минутный слот после R1; до R2 состав и путь свободны. Старший IS200 покрывает IS100 на том же рубеже.' },
    { id: 'trip-2', kind: 'TRIP', trainId: 'EVS-SYN-1', label: 'Рейс R2 · 670 км',
      startAt: '2028-07-01T01:30:00+03:00', endAt: '2028-07-01T02:00:00+03:00',
      source: 'synthetic fixed turn', reason: 'Заданный рейс; его время не меняется планировщиком.' },
    { id: 'trip-3', kind: 'TRIP', trainId: 'EVS-SYN-1', label: 'Рейс R3 · 670 км',
      startAt: '2028-07-01T02:40:00+03:00', endAt: '2028-07-01T03:10:00+03:00',
      source: 'synthetic fixed turn', reason: 'Заданный рейс; его время не меняется планировщиком.' }
  ]
}

export default function CalendarDemo() {
  return <>
    <p className="muted">Демонстрация F2 E2: фиксированные рейсы и цельный цикл обслуживания. Данные не связаны с текущим API-сценарием.</p>
    <PlanningCalendar data={data} />
  </>
}
