# StockReserve

Сервис резервирования товаров на складе.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
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
