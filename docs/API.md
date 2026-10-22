# REST API Campus Equipment

Базовый адрес: `http://localhost:8080`. Для `/api/*` нужен
`Authorization: Bearer <apiKey>`, для JSON — `Content-Type: application/json`.
UUID берите из ответов создания. Контракт: [openapi.json](openapi.json).

## Пользователи

- `GET /api/me` — текущий пользователь.
- `GET /api/users?limit=20&offset=0` — список, только ADMIN.
- `POST /api/users` — создание, только ADMIN; 201, поля `user` и `apiKey`.

```json
{"name":"Иван Иванов","email":"ivan@university.example","role":"USER"}
```

Роли: USER, STAFF, ADMIN. Почта нормализуется в нижний регистр.
Повторная почта — 409. Персональный ключ возвращается один раз.

## Оборудование

- `GET /api/equipment?category=LAPTOP&limit=20&offset=0` — каталог.
- `GET /api/equipment/{id}` — карточка.
- `POST /api/equipment` — STAFF/ADMIN; ответ 201 и Location.

```json
{"inventoryNumber":"NB-001","name":"Ноутбук Lenovo","category":"LAPTOP","location":"Кабинет 101","description":"16 ГБ RAM"}
```

`description` необязателен. Категория — произвольная непустая строка;
фильтр категории работает по точному совпадению.

- `GET /api/equipment/{id}/availability?start=2030-01-01T10:00:00Z&end=2030-01-01T12:00:00Z`
  — `{"available":true}`. Проверяет расписание и техническое состояние.
  Просроченный возврат предыдущей выдачи дополнительно проверяется при выдаче.
- `PATCH /api/equipment/{id}/status` — STAFF/ADMIN; состояния ACTIVE, MAINTENANCE, RETIRED.

```json
{"status":"MAINTENANCE","reason":"Замена аккумулятора"}
```

Списание необратимо. Обслуживание/списание атомарно отменяет подтверждённые брони.

## Бронирования

- `POST /api/bookings` — создание своей заявки; ответ 201, PENDING и Location.

```json
{"equipmentId":"<UUID оборудования>","start":"2030-01-01T10:00:00Z","end":"2030-01-01T12:00:00Z","purpose":"Лабораторная работа"}
```

Подставьте реальный UUID и будущие даты. Допустимо смещение часового пояса:
`2030-01-01T13:00:00+03:00`. В query-параметрах `+` нужно кодировать как `%2B`.

- `GET /api/bookings?status=APPROVED&overdue=false&limit=20&offset=0` — список;
  все фильтры необязательны. USER видит свои записи, STAFF/ADMIN — все.
- `GET /api/bookings/{id}` — заявка, владелец или STAFF/ADMIN.
- `GET /api/bookings/{id}/events` — история, те же права.
- `POST /api/bookings/{id}/approve` — STAFF/ADMIN; без тела или `{}`.
- `POST /api/bookings/{id}/reject` — STAFF/ADMIN: `{"reason":"Причина отказа"}`.
- `POST /api/bookings/{id}/cancel` — владелец или STAFF/ADMIN до выдачи:
  `{"reason":"Изменились планы"}`.
- `POST /api/bookings/{id}/issue` — STAFF/ADMIN: `{"condition":"Исправен, полный комплект"}`.
- `POST /api/bookings/{id}/return` — STAFF/ADMIN:
  `{"condition":"Исправен, полный комплект","damaged":false}`.

Изменение возвращает 200 и текущую заявку. Поля `issuedAt`, `issuedBy`,
`issueCondition`, `returnedAt`, `returnedBy`, `returnCondition` равны null
до соответствующей операции. `reason` — причина отказа/отмены.
`overdue` — вычисляемый признак. При возврате `damaged` обязателен и имеет тип boolean.

## Формат списков и ошибок

Списки: `{"items":[...]}` без общего количества. `limit` 1–100 (по умолчанию 20),
`offset` 0–1000000. Пользователи сортируются по email/UUID, оборудование —
по инвентарному номеру/UUID, брони — по времени создания убывающе/UUID,
история — по порядку записи событий. При изменениях между страницами offset-пагинации
возможны обычные смещения.

```json
{"error":{"code":"INVALID_TRANSITION","message":"Cannot perform operation on a RETURNED booking","requestId":"UUID"}}
```

`X-Request-Id` совпадает с идентификатором в ошибке и логах. Клиент ориентируется
на `code`, а не на текст `message`. Основные коды: VALIDATION_ERROR,
UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, EMAIL_EXISTS, DUPLICATE_RESOURCE,
BOOKING_CONFLICT, INVALID_TRANSITION, BOOKING_EXPIRED, OUTSIDE_BOOKING_PERIOD,
EQUIPMENT_UNAVAILABLE, EQUIPMENT_NOT_RETURNED, EQUIPMENT_RETIRED,
REFERENCE_CONFLICT, DATABASE_UNAVAILABLE.

Неизвестные/повторяющиеся JSON-поля и query-параметры, неверные типы и пустые
обязательные строки отклоняются. Тело ограничено 64 КиБ.

## Healthcheck и демонстрация

`GET /health/live` — работа HTTP (200), `GET /health` — проверка БД (200/503).
Ключ не нужен. Автоматический сценарий — `scripts/smoke.ps1` после запуска сервера.
