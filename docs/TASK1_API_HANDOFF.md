# Задача 1: граница backend/БД для F2 и frontend

Работа не меняет CP-SAT и frontend. Все времена в запросах — ISO 8601 с явным
смещением; для исходных планируемых фактов нужна точность до минуты.

## Готовый контракт вкладки «События»

Поддержаны пути и поля из `docs/UI_CONTRACT.md` и `frontend/src/changeRequests.ts`:
`POST /scenarios/{id}/requests`, `GET /requests`, `GET /requests/{id}`,
`GET /plans/active`. Здесь и ниже префикс `/api/v1`.
Первый POST возвращает 201; точный повтор — 200 с той же заявкой, номером и job.
Используются `clientRequestId`, `expectedSnapshotHash`, `payload`, `comment`.
Оригинальные payload/comment сохраняются без переименования в ответе.
Список UI показывает только представимые в его union виды и срочности;
расширенные заявки доступны через `/change-requests`, не маскируются под UI-политику.
Автор берётся из сессии; UI POST разрешён DISPATCHER/PLANNER, GET — всем трём ролям.

Публичные статусы совпадают с UI: APPLIED, CALCULATED, VALIDATED, APPROVED, FAILED.
RECEIVED и IN_CALCULATION во внутреннем журнале показываются как APPLIED,
поскольку новая версия уже записана. ERROR показывается как FAILED с code/message.
REJECTED зарезервирован контрактом; ручной метод отклонения в этом блоке не добавлен.
`validationStatus` и автор/время согласования читаются из сохранённого результата/истории.

UI POST в одной транзакции ставит один job в durable queue: BLOCKS_CP_SAT,
seed=1, timeLimitSec=30, frozenMinute=0. Это настройки автопересчёта, не нормативы.
Отключение: `okno.requests.auto-calculate=false`. Утверждение остаётся за PLANNER.
GET списка и существующий опрос фронта каждые 5 секунд доставляют изменения.

`scenarioId` в UI ответе и календарях — постоянный rootId. Идентификаторы рейсов
сохраняются, поэтому сравнение двух календарей работает. План и snapshot хранят
точную версию источника. Дополнительные поля ответа: `newScenarioId`, `snapshotId`,
`sourceVersion`. Для ручного расчёта использовать newScenarioId, не rootId.
`GET /scenarios/{rootId}/version` сообщает актуальные ID/hash для следующей заявки.

**Что остаётся коллеге по UI:** транспортные вызовы работают без правок типов,
но Events.tsx берёт hash из старого действующего плана. После первой заявки данные
изменились: следующая новая заявка с прежним hash получит корректный 409.
Нужно обновлять head через `/scenarios/{rootId}/version`, не переписывать старый календарь.
Для согласования рассчитанного по заявке плана открывать её planId в Planning;
сейчас InboxSection показывает детали и сравнение, но не подключает этот planId
к экрану согласования. Сохранённые mock-заявки автоматически не импортируются.
После установки backend обновить страницу, чтобы сбросить ready.requests=false.

Срочность UI сохраняется точно: IMMEDIATE, WITHIN_24H, WITHIN_HORIZON.
WITHIN_24H даёт предел min(обнаружение + 24 часа, конец горизонта, явный dueBy);
WITHIN_HORIZON без dueBy ограничен концом горизонта. IMMEDIATE — требование
запрета следующего выпуска до устранения, не приказ прервать текущий рейс и
не назначенный слот. Сам запрет выпуска и учёт срочных работ подключает задача 2.
До её адаптера срочная заявка остаётся в БД, job и UI статус становятся FAILED
с UNSUPPORTED_SOURCE_FACTS; согласованного нового плана нет.

## Расширенный backend-контракт

`POST /api/v1/change-requests`: `{scenarioId, expectedVersion, idempotencyKey,
reason, source, change}`. `scenarioId` означает конкретную неизменяемую версию.
Список версий: `GET /scenarios/{id}/versions`; исходный snapshot:
`GET /scenarios/{id}/source`; актуальная версия: `GET /scenarios/{id}/version`.
Ответ заявки связывает requestId, родительскую и новую версию, snapshotId/hash.

Варианты `change.kind`: TRIP_CHANGE, TRIP_ADD, TRIP_CANCEL,
URGENT_MAINTENANCE, RESOURCE_OUTAGE. Правила меняет только технолог через
`POST /scenarios/{id}/rule-versions` с тем же конвертом и RULE_CHANGE.
POST требует CSRF и действующую сессию. Идентификатор автора берётся из сессии.

Точная повторная отправка с тем же ключом возвращает ту же заявку, даже если
head уже изменился. Другой payload с тем же ключом — 409 IDEMPOTENCY_KEY_REUSED.
Устаревшая expectedVersion/scenarioId — 409 VERSION_CONFLICT.
Неверные идентификаторы, интервалы и пересечения — 422 INVALID_REQUEST.
Транзакция сохраняет сразу факты, версию, snapshot/hash, заявку и RECEIVED.

Статусы RECEIVED → IN_CALCULATION → CALCULATED → VALIDATED → APPROVED.
ERROR содержит конкретный code/message. Статусы не назначаются клиентом.
`GET /change-requests/updates?after=0` и SSE `/change-requests/stream`
доставляют новые события без ручной перезагрузки. SSE поддерживает Last-Event-ID.
Повторный расчёт может вернуть заявку из ERROR в IN_CALCULATION; история остаётся.

F2 получает source snapshot новой версии. urgentWorkRequirements содержат
потребность, earliest_start_at, deadline_at/urgency и work_kind. Это не
закреплённые работы. Пока адаптер не учитывает эти поля, расчёт должен явно
завершаться ошибкой UNSUPPORTED_SOURCE_FACTS, а не игнорировать заявку.
resourceOutages перечисляются в snapshot; окна доступности уже уменьшены.
Новые ключи добавляются только при наличии соответствующих фактов, поэтому
прежние пустые E2-сценарии сохраняют прежний формат/hash. D2 E2 отклоняет
неизвестные непустые поля. F2 подключает Spring bean `SourcePlanningAdapter`,
реализуя проекцию `SourceSnapshotRepository.SourceSnapshot` →
`MileageObligationGenerator.Projection`. Не нужно переписывать durable queue.
В main 35b9702 появился E3 assessment и SourceSnapshotE3Adapter. Этот класс не
реализует SourcePlanningAdapter и не учитывает Task1 urgentWorkRequirements/
resourceOutages: его нельзя просто зарегистрировать как общий bean. Нужны явная
маршрутизация E2/E3, учёт новых требований и соответствующее расширение D2.
Текущий assessment не означает PASS или разрешение полного расчёта FULL43.

Jobs/plans сохраняются в PostgreSQL. current-plan возвращает согласованный план,
а не последний расчёт. `/scenarios/{id}/plan-selection` отдельно возвращает
latestDraftId и effectivePlanId и признак совпадения с текущими данными.
Старый согласованный план не становится актуальным для новой версии автоматически.
Согласование нового плана требует роли PLANNER, текущей версии источника и PASS D2.

Старый `/incidents` сохраняется как совместимый текстовый журнал. Его запись
сама по себе не меняет расписание: новые формы должны использовать change-requests.

## Проверка после перезапуска

`database/tests/request_restart_smoke.ps1` запускает отдельный API на 127.0.0.1:18089
с переданной тестовой БД, меняет R1 на +5 минут, проводит расчёт/D2/согласование,
принудительно перезапускает именно этот процесс и проверяет старый/new snapshot,
повтор, jobs/plans, действующий план, журнал и SSE Last-Event-ID.
Не запускать smoke против рабочей БД команды: он добавляет тестовые сценарии.
Использовать Java 21 и заранее собрать backend JAR. Он не останавливает Docker
или другие API. Результат — backend/target/task1-restart-proof.json.
