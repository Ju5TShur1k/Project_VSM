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

Backend сейчас работает на мок-данных (`PlanningService`, in-memory `Store`) —
job-сервис мгновенно возвращает пустой план вместо реального CP-SAT-расчёта.
Самостоятельный CP-SAT Planner находится в `backend/.../planning`; профиль `database` создаёт схему PostgreSQL и включает репозиторий исходных снимков D1.
Связывание HTTP API, snapshot, асинхронного job, Planner и проверки D2 ещё не выполнено.
