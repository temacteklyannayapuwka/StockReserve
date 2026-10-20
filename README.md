# StockReserve

Сервис резервирования товаров на складе.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Создание товара через `POST /api/products` и просмотр через `GET /api/products/{id}`.
- Резервирование через `POST /api/reservations` и просмотр через `GET /api/reservations/{id}`.
- Подтверждение и отмена резервов с однократным возвратом товара при отмене.
- Атомарное уменьшение доступного остатка и защита от одновременного резервирования сверх наличия.
- Проверка названия (1–120 символов, не только пробелы) и целого неотрицательного остатка.
- Хранение названия и количества товара в PostgreSQL через JPA.
- Создание схемы базы данных миграциями Flyway.
- Запрет отрицательных остатков и пустых названий на уровне базы данных.
- Проверка состояния через `GET /actuator/health`.

## Запуск

Требуются Java 17, Maven 3.6.3 или новее и PostgreSQL 16.

Создайте пользователя и базу данных в PostgreSQL:

```sql
CREATE USER stockreserve WITH PASSWORD 'local-password';
CREATE DATABASE stockreserve OWNER stockreserve;
```

Настройте подключение:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/stockreserve'
export DB_USER='stockreserve'
export DB_PASSWORD='local-password'
```

Соберите и запустите приложение:

```bash
mvn -DskipTests clean package
java -jar target/stockreserve-0.0.1-SNAPSHOT.jar
```

Создание товара:

```bash
curl -i -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard","quantity":5}'
```

Ответ `201 Created` содержит `id`, `name`, `quantity` и заголовок `Location` с адресом товара.
Пробелы по краям названия удаляются.

Для просмотра выполните `GET` по адресу из `Location`.
Неверные данные возвращают `400 Bad Request`, неизвестный товар — `404 Not Found`.

Резервирование товара:

```bash
curl -i -X POST http://localhost:8080/api/reservations \
  -H 'Content-Type: application/json' \
  -d '{"productId":"UUID товара","orderId":"UUID заказа","quantity":2}'
```

Подставьте ID созданного товара и UUID заказа. Количество должно быть целым положительным числом.
Ответ `201 Created` содержит `id`, `productId`, `orderId`, `quantity`, `status: ACTIVE`
и заголовок `Location` с адресом резерва. Для просмотра выполните `GET` по этому адресу.

Поле `quantity` товара показывает доступный остаток: при создании резерва оно уменьшается.
При нехватке товара возвращается `409 Conflict`, при неизвестном товаре или резерве — `404 Not Found`.
Неверные данные возвращают `400 Bad Request`. Каждый успешный `POST /api/reservations` создаёт отдельный резерв.

Подтверждение резерва:

```bash
curl -X POST http://localhost:8080/api/reservations/UUID_резерва/confirm
```

Отмена резерва:

```bash
curl -X POST http://localhost:8080/api/reservations/UUID_резерва/cancel
```

Подставьте ID резерва. Обе операции возвращают `200 OK` с данными резерва.
Подтверждение переводит `ACTIVE` в `CONFIRMED` без дополнительного списания.
Отмена переводит `ACTIVE` в `CANCELLED` и возвращает количество в доступный остаток.
Повтор той же операции возвращает текущий результат без изменения остатка.
Отмена подтверждённого резерва и подтверждение отменённого возвращают `409 Conflict`.

Проверка состояния:

```bash
curl http://localhost:8080/actuator/health
```

Ответ:

```json
{"status":"UP"}
```

Для тестов нужен запущенный Docker: Testcontainers создаёт отдельную временную PostgreSQL.

```bash
mvn clean verify
```
