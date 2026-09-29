# Контракт интерфейса и API заявок

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

В профиле database `/current-plan` и `/plans/active` отдают действующий согласованный план. Последний черновик доступен отдельно через `/scenarios/{id}/plan-selection`; он не заменяет действующий план.

Сравнение «было → стало» интерфейс считает сам по двум календарям (`/plans/{id}/calendar`): рейсы сопоставляются по `id`, работы ТО — по составу, циклу и порядку. Отдельный метод сравнения на сервере не нужен.

## Что нужно от календаря

Уже есть: `events[]` с `id`, `kind`, `trainId`, `resourceId`, `label`, `startAt`, `endAt`. Желательно добавить `distanceKm` для рейсов: сейчас расстояние берётся из подписи «· 670 км».

## Интеграция задачи 1

Пути и типы выше реализованы в профиле database. UI ответ дополнительно содержит
`newScenarioId`, `snapshotId`, `sourceVersion`; они не ломают существующие типы.
UI scenarioId и calendar.scenarioId стабильны внутри семейства версий.
Точный источник хранится в Plan.scenarioId, snapshotId/hash.
После заявки следующий POST должен использовать актуальный hash из
`GET /scenarios/{rootId}/version`, а не hash прежнего согласованного плана.
Автопересчёт создаёт job, его результат доступен через ChangeRequest.planId;
его нужно связать с экраном согласования. Подробности: `docs/TASK1_API_HANDOFF.md`.
