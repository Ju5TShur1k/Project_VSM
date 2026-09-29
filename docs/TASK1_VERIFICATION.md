# Задача 1: проверка 29.09.2026

База кода: main `35b9702`, включая UI задачи 3 и E3 assessment коллег.
Task1 не меняет frontend или алгоритмы CP-SAT.

Проверено на Java 21.0.10, Maven 3.9.16, отдельном PostgreSQL 18.3
на 127.0.0.1:55440. БД команды и её Docker не изменялись.
Целевой PostgreSQL 16 в Compose отдельно в этом прогоне не запускался.

| Проверка | Результат |
|---|---|
| Полный `mvn verify` с D1_TEST_DB_URL | 114 тестов, 0 failures/errors/skipped, JAR собран |
| ChangeRequestIntegrationTest | 14 тестов: транзакции, роли, версии, повтор/конкуренция, UI, очередь, lease, D2/approval, журнал |
| Flyway / DatabaseProfileTest | V1–V7, 32 таблицы; канонический SHA-256 и сохранность snapshots |
| Готовый frontend main | `tsc -b` и `vite build` прошли, frontend не редактировался |
| OpenAPI YAML | Разобран SnakeYAML; все 114 локальных ссылок разрешены |
| Реальный HTTP и принудительный рестарт API | PASS; cookie login, CSRF, UI POST, D2, approval, восстановление, SSE |
| `git diff --check` | Без ошибок |

## Фактический опыт перезапуска

Скрипт: `database/tests/request_restart_smoke.ps1`. Он запустил отдельный API
на 127.0.0.1:18089, создал модельный источник, сдвинул конкретный R1 на 5 минут,
дождался автоматического расчёта, D2 PASS и согласовал план от planner.
Затем принудительно остановил и заново запустил только этот процесс.

После повторного входа проверены: прежний рейс в старой версии, +5 минут в новой,
разные hash, тот же requestId при повторе, сохранённые job/plan,
действующий согласованный план, автор/история, текстовый журнал и SSE Last-Event-ID.

Доказательство успешного запуска (из backend/target/task1-restart-proof.json):

- requestId: `f0750be5-d987-40eb-8fcc-9bd5492521b5`;
- sourceVersion: 1, newScenarioId: `20d7bd96-1584-43db-9c9d-07db88694d94`;
- jobId: `4d1e545a-e938-441e-af62-d9e366d51bf8`;
- planId: `642f97fe-a21f-4981-be4c-1846999e3ebf`, status APPROVED;
- old hash: `5f46cb252df236407b38175688895336f43d6ddfc48c7c1abab1a4f5bb9f6b92`;
- new hash: `dd9f138f7b94637b8c8e01379f07ea51761c3a073f8ca3729b4c39af1189e1bc`;
- история: RECEIVED → IN_CALCULATION → CALCULATED → VALIDATED → APPROVED.

Это IDs отдельной тестовой БД, не данные оператора и не рабочие IDs команды.

## Границы результата

Сохранение срочной заявки реализовано; подбор слота и запрет следующего выпуска
при IMMEDIATE — задача 2. Без её адаптера job завершается явным
UNSUPPORTED_SOURCE_FACTS, не игнорирует потребность и не разрешает согласование.
E3 assessment main не означает полного планирования парка или PASS D2.

Фронту задачи 3 остаются обновление source head/hash после заявки и открытие
request.planId в экране согласования. Пути, payload и публичные статусы уже
соответствуют UI_CONTRACT; подробности: TASK1_API_HANDOFF.md.

Проверка доказывает свойства хранения/API на модельных данных, не подтверждает
бизнес-нормативы и не даёт производственного разрешения на выпуск состава.
