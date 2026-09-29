# ОКНО ВСМ

Планирование обслуживания парка ЭВС360 (кейс №6, АО «СВС»). Контекст и ТЗ — в материалах команды.

## Запуск

Основной демонстрационный путь и критерий изменения рейса: [docs/F2_defense_demo.md](docs/F2_defense_demo.md).
Схема D1 и работа с PostgreSQL: [database/README.md](database/README.md).
Для БД на порту 5433: `docker compose -f docker-compose.yml -f docker-compose.database.yml up -d --build`.

```bash
docker compose up --build
```

- API: http://localhost:8080 (health: `/actuator/health`)
- UI: http://localhost:5173

Без Docker:

```bash
cd backend && ./mvnw -B package -DskipTests && java -jar target/okno-api-0.0.1-SNAPSHOT.jar
cd frontend && npm install && npm run dev
```

## Вход

API закрыто сессией (cookie) + CSRF (заголовок `X-XSRF-TOKEN` = значение cookie `XSRF-TOKEN`). Демо-аккаунты из `backend/src/main/resources/application.yml`:
`planner` / `planner-demo`, `technologist` / `tech-demo`, `dispatcher` / `disp-demo`. Пароли переопределяются переменными `OKNO_PLANNER_PASSWORD`, `OKNO_TECH_PASSWORD`, `OKNO_DISPATCHER_PASSWORD`.

| Роль | Что может |
|---|---|
| Планировщик | Загрузка данных, расчёт, карточка поезда, журнал сообщений, **согласование** |
| Технолог | То же, кроме согласования |
| Диспетчер | Сообщает о событиях (изменение рейса, неотложное ТО, отказ оборудования), видит текущий план в календаре без подробностей по пробегу |

Роли проверяет сервер (Spring Security), интерфейс только прячет недоступное. В профиле database заявки вкладки «События» сохраняются в PostgreSQL, создают новую версию источника и ставят расчёт в очередь. Совместимый текстовый `/incidents` — отдельный журнал, он не меняет расписание.
Согласующий в плане (`approvedBy`) берётся из сессии, а не из тела запроса.

## Структура

- `backend/` — Spring Boot 4 API (Java 21, сгенерирован через [Spring Initializr](https://start.spring.io)), пакет `com.vsm.okno`. Контракт: `contracts/openapi.yaml`. Есть Maven wrapper (`./mvnw`), системный Maven не обязателен.
- `frontend/` — React + Vite, экран «Парк».
- `contracts/` — OpenAPI-спецификация, источник правды для DTO.
- `docker-compose.yml` — postgres + api + web.

## Статус

По умолчанию главная страница предлагает FULL43: 43 состава и оперативную реакцию на отказ перед рейсом. Для первых суток доступны снятие состава с оставшегося оборота, проверка кандидатур и согласование замены, дефицит резерва по городам и отдельная приёмка ремонта. Решения сохраняются версиями в PostgreSQL и восстанавливаются по ссылке после перезапуска API. Порядок демонстрации и границы результата — [docs/OPERATIONAL_FAILURE_DEMO.md](docs/OPERATIONAL_FAILURE_DEMO.md).

Наборы E2_6 (6 составов, 252 рейса) и BLOCKED6 запускают прежний путь: исходные рейсы в PostgreSQL → immutable snapshot D1 → адаптер E2 → CP-SAT F2 → независимая проверка D2 → календарь из API. Подробности и происхождение данных — [database/CASE_DATASET.md](database/CASE_DATASET.md). Численные нормы большого набора взяты из кейса; одометры, расписание и история модельные. Короткий опыт со сдвигом R1 сохраняет синтетические длительности.

Старый HTTP-импорт синтетического парка F1 сохранён для разработки и продолжает использовать внутреннюю проекцию без рейсов. Ограничения этого потока: [docs/F1_planner_wiring.md](docs/F1_planner_wiring.md).

- Профиль `database` подключает D2-проверку E2 по сохранённому источнику: [docs/D2_VALIDATION.md](docs/D2_VALIDATION.md). PASS связан с hash событий и версией; изменённый или непроверенный план не утверждается. E2_MODEL не означает фактический выпуск состава.
- `docker compose up --build` включает профиль `database`: заявки, версии источника, задания, планы и согласования сохраняются в PostgreSQL и переживают рестарт API. Запуск без профиля сохраняет прежний in-memory API F1.
- Контракт вкладки «События», версий и очереди: [docs/TASK1_API_HANDOFF.md](docs/TASK1_API_HANDOFF.md). `/plans/active` и `/current-plan` возвращают согласованный план; новый черновик не заменяет его. Учёт срочных требований в алгоритме и обновление head на фронте остаются точками интеграции задач 2/3.
- FULL43 имеет оперативный сценарий первых суток; план ТО полного парка на 14 суток пока запрещён до полного контракта E3. Оперативный сценарий не выдаёт PASS D2 за полный план и не рассчитывает КЭГ. Модули проверки E3 подготовлены, их подключение к полному плану остаётся следующим этапом. Базовое сравнение алгоритмов: [docs/D2_COMPARISON.md](docs/D2_COMPARISON.md).

## Тесты и JDK

```bash
cd backend && ./mvnw test
```

Планировщик использует нативные библиотеки OR-Tools. На Windows JDK с устаревшей `msvcp140.dll` в своей папке `bin` (например JDK 22 из `.jdks`) валит JVM при первом расчёте (`EXCEPTION_ACCESS_VIOLATION`). Запускайте на JDK 21 или 25 с актуальной библиотекой. Docker/Linux это не затрагивает.
