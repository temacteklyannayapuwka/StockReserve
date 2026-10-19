# StockReserve

Сервис резервирования товаров на складе.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Проверка состояния через `GET /actuator/health`.

## Запуск

Требуются Java 17 и Maven 3.6.3 или новее.

```bash
mvn clean package
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
