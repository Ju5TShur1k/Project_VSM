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
`planner` / `planner-demo`, `technologist` / `tech-demo`. Пароли переопределяются переменными `OKNO_PLANNER_PASSWORD`, `OKNO_TECH_PASSWORD`.
Согласующий в плане (`approvedBy`) берётся из сессии, а не из тела запроса.

## Структура

- `backend/` — Spring Boot 4 API (Java 21, сгенерирован через [Spring Initializr](https://start.spring.io)), пакет `com.vsm.okno`. Контракт: `contracts/openapi.yaml`. Есть Maven wrapper (`./mvnw`), системный Maven не обязателен.
- `frontend/` — React + Vite, экран «Парк».
- `contracts/` — OpenAPI-спецификация, источник правды для DTO.
- `docker-compose.yml` — postgres + api + web.

## Статус

Главная страница запускает демонстрационный E2-путь: исходные рейсы в PostgreSQL → immutable snapshot D1 → адаптер E2 → CP-SAT F2 → статус D2 → календарь из API. Изменение времени R1 создаёт новый hash, старый snapshot остаётся в базе. Все данные и длительности синтетические.

Старый HTTP-импорт синтетического парка F1 сохранён для разработки и продолжает использовать внутреннюю проекцию без рейсов. Ограничения этого потока: [docs/F1_planner_wiring.md](docs/F1_planner_wiring.md).

- Пока нет полной независимой проверки D2 (`PlanValidator`), статус `NOT_PERFORMED`; при `OPTIMAL/FEASIBLE` solver и без CRITICAL-нарушений согласование разрешено **с оговоркой**: план получает статус `APPROVED`, а `validationStatus` остаётся `NOT_PERFORMED`, в UI — «Согласован без независимой проверки D2». Когда D2 подключит валидатор, согласование снова потребует `PASS`.
- `docker compose up --build` теперь включает профиль `database`; запуск backend без профиля сохраняет прежний in-memory API F1. Задания и планы ещё живут в памяти процесса, хотя исходный snapshot сохраняется в PostgreSQL.
- Сквозной UI-путь использует один синтетический E2-состав. E3-факты (уборка, резерв, frozen work) не выводятся из этого снимка и не заявлены как проверенные.

## Тесты и JDK

```bash
cd backend && ./mvnw test
```

Планировщик использует нативные библиотеки OR-Tools. На Windows JDK с устаревшей `msvcp140.dll` в своей папке `bin` (например JDK 22 из `.jdks`) валит JVM при первом расчёте (`EXCEPTION_ACCESS_VIOLATION`). Запускайте на JDK 21 или 25 с актуальной библиотекой. Docker/Linux это не затрагивает.
