# F1: подключение планировщика F2 к HTTP API

Запись отображения между HTTP-контрактом и внутренним `Planner`, как просили F2/D1/D2. Статус: **работает на синтетическом входе**, проверка D2 отсутствует.

## Поток

```
POST /planning-jobs  ->  job QUEUED (202)
   worker-поток: SyntheticSnapshot -> PlannerRequest -> Planner.plan -> PlannerResult
                 -> Plan (events + validations) -> job SUCCEEDED, solverStatus = статус солвера
GET  /planning-jobs/{id}  (опрос)   GET /plans/{id}
POST /plans/{id}/approve  -> 422 PLAN_NOT_APPROVABLE, если есть CRITICAL-нарушение
```

- Статус задания (`QUEUED/RUNNING/SUCCEEDED/FAILED`) и `solverStatus` (`OPTIMAL/FEASIBLE/INFEASIBLE/UNKNOWN/MODEL_INVALID`) независимы. `SUCCEEDED` + `INFEASIBLE` — нормальный исход: создаётся план без работ с нарушением `SOLVER_INFEASIBLE`.
- Исключение или ошибка загрузки нативных библиотек (`LinkageError`) переводят задание в `FAILED` с текстом в `error`.
- Один рабочий поток внутри процесса API, задания идут по очереди и теряются при перезапуске (`ponytail` в `PlanningService`). Переход на очередь в PostgreSQL — после подключения БД D1.

## Отображение полей

| HTTP | Внутри |
|---|---|
| `JobRequest.policy` | `PlannerRequest.Policy` (`BLOCKS_CP_SAT`, `WHOLE_CYCLE_CP_SAT` -> `CpSatPlanner`; `WHOLE_CYCLE_EDD` -> `EarliestDueDatePlanner`), неизвестное значение -> 422 |
| `seed`, `timeLimitSec` | `seed` (32 бита), лимит 1..300 с (клиент не может занять решатель надолго) |
| `frozenUntil` (время со смещением) | целые минуты от начала горизонта, `PlannerRequest` схемы `1.1`; не на границе минуты или за горизонтом -> 422. Закреплённые работы прошлого плана пока не передаются |
| `PlannerResult.blocks[]` | `Plan.events[]`: `id` = blockId, `kind` = `SERVICE_BLOCK`, `startAt/endAt` ISO с смещением, `resourceIds` = [resourceId] |
| `PlannerResult.diagnostics[]` | `Plan.validations[]` с `severity = CRITICAL` (нет допустимого плана блокирует утверждение) |
| `PlanValidator` (интерфейс в `service`) | добавляется в `Plan.validations[]` |

## Вход планировщика (синтетический)

`SyntheticSnapshot` строит снапшот схемы `1.4` из списка поездов сценария. Всё помечено `synthetic`, реальных рейсов, пробегов и календарей нет.

- горизонт 14 суток с 2028-07-01 00:00 +03:00; 4 поезда `HOT_RESERVE` защищены от работ, для остальных действует правило четырёх поездов в резерве;
- каждому рабочему поезду один блок IS100 на 120 минут (длительность из кейса), пути `TRACK_1/TRACK_2` (число путей неизвестно);
- `MACHINE_DOWN` = простой `TRACK_1` на 12 часов (несколько сбоев идут подряд), `UNPLANNED_INSPECTION` = дополнительный блок на 4 часа (синтетика);
- `snapshotHash` = SHA-256 от канонического текста входа. Для реальных данных его заменит хэш D1.

## Что нужно от других

- **D2:** реализовать `PlanValidator` как Spring-бин, заглушка «не выполнена» отключится сама. Пока её нет, каждый план получает `VALIDATION_NOT_PERFORMED` (CRITICAL), и утверждение недоступно. Это намеренно.
- **D1:** заменить `SyntheticSnapshot` проекцией канонического снапшота (реальные рейсы, история циклов, окна ресурсов) и хранить задания/планы в БД.
- **A1:** подтверждённые блоки и длительности вместо синтетических.

## Проверка

`FlowSmokeTest` (сквозной поток с заглушкой-валидатором), `PlannerWiringTest` (утверждение без валидатора запрещено, сбой сдвигает работы с пути, невалидный ввод -> 422). Нужны нативные библиотеки OR-Tools, см. примечание про JDK в README.
