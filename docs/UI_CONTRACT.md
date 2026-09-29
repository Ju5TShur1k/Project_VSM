# Контракт интерфейса и API заявок (черновик задачи 3 для задачи 1)

Что фронтенд ждёт от сервера. Типы в коде: `frontend/src/changeRequests.ts`. Пока сервер отвечает на эти пути 404/400/403, интерфейс работает на локальном макете и пишет об этом на экране; макет только сохраняет заявку со статусом «Получена» и не изображает расчёт или проверку. Как только метод появится, фронтенд переключится на него сам, без правок.

## Заявка

```
POST /api/v1/scenarios/{scenarioId}/requests       роли: DISPATCHER, PLANNER
{ clientRequestId, expectedSnapshotHash, payload, comment }
201 → ChangeRequest    повтор того же clientRequestId → 200 и та же заявка
409 → expectedSnapshotHash устарел (данные уже изменились)
422 → неизвестный trainId/tripId, время прибытия не позже отправления и т. п.

GET /api/v1/requests?scenarioId=                    все роли, новые первыми
GET /api/v1/requests/{id}
```

`payload` — одно из двух:

| kind | Поля |
|---|---|
| `TRIP_CHANGE` | `trainId`, `tripId`, `newDepartureAt`, `newArrivalAt` (ISO со смещением), `reason`, `source` |
| `URGENT_MAINTENANCE` | `trainId`, `problem`, `detectedAt`, `notBeforeTripEnd` (bool), `urgency` (`IMMEDIATE` / `WITHIN_24H` / `WITHIN_HORIZON`), `dueBy` (ISO или null), `workType` |

`ChangeRequest`: `id, number, scenarioId, baseSnapshotHash, status, payload, comment, createdBy, createdAt, newSnapshotHash, jobId, planId, validationStatus, decidedBy, decidedAt, error {code, message}`.

Статусы: `RECEIVED → APPLIED` (новая версия данных и snapshot) `→ CALCULATED` (job завершён, есть `planId`) `→ VALIDATED` (D2 PASS) `→ APPROVED`; либо `REJECTED` / `FAILED` с `error`. Согласует всегда человек.

## Планы

```
GET /api/v1/plans/active        действующий согласованный план (Plan) или 204
```

Сейчас `/current-plan` отдаёт последний рассчитанный план любого сценария; диспетчер и мастер должны видеть действующий согласованный. Пока `/plans/active` нет, интерфейс берёт `/current-plan` и показывает статус плана рядом.

Сравнение «было → стало» интерфейс считает сам по двум календарям (`/plans/{id}/calendar`): рейсы сопоставляются по `id`, работы ТО — по составу, циклу и порядку. Отдельный метод сравнения на сервере не нужен.

## Что нужно от календаря

Уже есть: `events[]` с `id`, `kind`, `trainId`, `resourceId`, `label`, `startAt`, `endAt`. Желательно добавить `distanceKm` для рейсов: сейчас расстояние берётся из подписи «· 670 км».
