# ОКНО ВСМ

Планирование обслуживания парка ЭВС360 (кейс №6, АО «СВС»). Контекст и ТЗ — в материалах команды.

## Запуск

Профиль PostgreSQL/Flyway, схема D1, синтетические данные и инструкции команде: [database/README.md](database/README.md).
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
`planner` / `planner-demo`, `technologist` / `tech-demo`. Пароли переопределяются переменными `OKNO_PLANNER_PASSWORD`, `OKNO_TECH_PASSWORD`.
Согласующий в плане (`approvedBy`) берётся из сессии, а не из тела запроса.

## Структура

- `backend/` — Spring Boot 4 API (Java 21, сгенерирован через [Spring Initializr](https://start.spring.io)), пакет `com.vsm.okno`. Контракт: `contracts/openapi.yaml`. Есть Maven wrapper (`./mvnw`), системный Maven не обязателен.
- `frontend/` — React + Vite, экран «Парк».
- `contracts/` — OpenAPI-спецификация, источник правды для DTO.
- `docker-compose.yml` — postgres + api + web.

## Статус

`POST /planning-jobs` запускает CP-SAT планировщик F2 (асинхронно, в отдельном потоке) на **синтетическом** входе, собранном из списка поездов сценария; сбои («станок недоступен», «внеплановый осмотр») меняют вход и результат. Отображение и ограничения: [docs/F1_planner_wiring.md](docs/F1_planner_wiring.md).

- Пока нет проверки D2 (`PlanValidator`), каждый план несёт критическое нарушение `VALIDATION_NOT_PERFORMED`, и **утверждение плана недоступно** (422). Так задумано.
- Хранилище по умолчанию в памяти. Профиль `database` создаёт схему PostgreSQL и включает репозиторий снимков D1, но задания и планы туда пока не пишутся.
- Реальные рейсы, пробеги и календари ресурсов планировщик из HTTP API ещё не получает.

## Тесты и JDK

```bash
cd backend && ./mvnw test
```

Планировщик использует нативные библиотеки OR-Tools. На Windows JDK с устаревшей `msvcp140.dll` в своей папке `bin` (например JDK 22 из `.jdks`) валит JVM при первом расчёте (`EXCEPTION_ACCESS_VIOLATION`). Запускайте на JDK 21 или 25 с актуальной библиотекой. Docker/Linux это не затрагивает.
