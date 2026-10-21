package dev.bakanov.stockreserve.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bakanov.stockreserve.product.Product;
import dev.bakanov.stockreserve.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.password=", "reservation.expiration.enabled=false"})
class ReservationIdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ProductRepository products;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    ReservationService service;

    @Autowired
    IdempotentReservationService idempotent;

    @Autowired
    ReservationExpirationService expiration;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM reservation_requests");
        reservations.deleteAll();
        products.deleteAll();
    }

    @Test
    void identicalRepeatReturnsSameReservationWithoutChangingStockOrDeadline() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);
        String key = UUID.randomUUID().toString();

        var first = send(key, request);
        var repeated = send(key, request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(repeated.getStatusCode().value()).isEqualTo(201);
        assertThat(repeated.getBody()).isEqualTo(first.getBody());
        assertThat(repeated.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "order", "quantity"})
    void sameKeyWithChangedDataReturnsConflict(String changedField) {
        var firstProduct = products.saveAndFlush(new Product("Keyboard", 5));
        var secondProduct = products.saveAndFlush(new Product("Mouse", 5));
        var order = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        assertThat(send(key, new CreateReservationRequest(firstProduct.getId(), order, 2))
                .getStatusCode().value()).isEqualTo(201);
        var changed = new CreateReservationRequest(
                changedField.equals("product") ? secondProduct.getId() : firstProduct.getId(),
                changedField.equals("order") ? UUID.randomUUID() : order,
                changedField.equals("quantity") ? 3 : 2);

        var response = send(key, changed);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(products.findById(firstProduct.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void distinctKeysAllowDistinctReservationsForSameRequest() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);

        var first = send(UUID.randomUUID().toString(), request);
        var second = send(UUID.randomUUID().toString(), request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().path("id")).isNotEqualTo(first.getBody().path("id"));
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(2);
    }

    @Test
    void insufficientStockDoesNotConsumeKey() {
        var product = products.saveAndFlush(new Product("Keyboard", 0));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 1);
        String key = UUID.randomUUID().toString();

        assertThat(send(key, request).getStatusCode().value()).isEqualTo(409);
        assertThat(requestCount()).isZero();
        assertThat(reservations.count()).isZero();
        jdbc.update("UPDATE products SET quantity = 2 WHERE id = ?", product.getId());
        assertThat(send(key, request).getStatusCode().value()).isEqualTo(201);

        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void unknownProductDoesNotConsumeKey() {
        String key = UUID.randomUUID().toString();
        var order = UUID.randomUUID();
        assertThat(send(key, new CreateReservationRequest(UUID.randomUUID(), order, 1))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(requestCount()).isZero();
        var product = products.saveAndFlush(new Product("Keyboard", 5));

        assertThat(send(key, new CreateReservationRequest(product.getId(), order, 1))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel", "expire"})
    void repeatsReturnCurrentTerminalStateWithoutCreatingAnotherReservation(String action) {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);
        String key = UUID.randomUUID().toString();
        var first = send(key, request);
        assertThat(first.getBody()).isNotNull();
        var id = UUID.fromString(first.getBody().path("id").asText());
        switch (action) {
            case "confirm" -> service.confirm(id);
            case "cancel" -> service.cancel(id);
            default -> {
                jdbc.update("UPDATE reservations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?", id);
                assertThat(expiration.expireNext()).isTrue();
            }
        }
        var expected = http.getForEntity("/api/reservations/" + id, JsonNode.class);

        var repeated = send(key, request);

        assertThat(repeated.getStatusCode().value()).isEqualTo(201);
        assertThat(repeated.getBody()).isEqualTo(expected.getBody());
        assertThat(repeated.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity())
                .isEqualTo(action.equals("confirm") ? 3 : 5);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-uuid", "1-1-1-1-1",
            "00000000-0000-0000-0000-00000000000g"})
    void rejectsMalformedKeyWithoutChangingStock(String key) {
        var product = products.saveAndFlush(new Product("Keyboard", 5));

        var response = send(key, new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(reservations.count()).isZero();
        assertThat(requestCount()).isZero();
    }

    @Test
    void uuidLetterCaseDoesNotCreateDifferentKeys() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);
        String key = "71adc462-98d5-4909-9bcc-5a8eaf74a92e";
        var first = send(key, request);

        var repeated = send(key.toUpperCase(Locale.ROOT), request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(repeated.getStatusCode().value()).isEqualTo(201);
        assertThat(repeated.getBody()).isEqualTo(first.getBody());
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateOneReservation() throws Exception {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);
        String key = UUID.randomUUID().toString();
        List<Callable<ResponseEntity<JsonNode>>> tasks = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<ResponseEntity<JsonNode>>) () -> send(key, request)).toList();

        var responses = race(tasks);

        assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode().value()).isEqualTo(201));
        assertThat(responses).extracting(ResponseEntity::getBody).containsOnly(responses.get(0).getBody());
        assertThat(responses).extracting(response -> response.getHeaders().getLocation())
                .containsOnly(responses.get(0).getHeaders().getLocation());
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "product"})
    void concurrentConflictingRequestsChooseOnePayload(String changedField) throws Exception {
        var firstProduct = products.saveAndFlush(new Product("Keyboard", 5));
        var secondProduct = products.saveAndFlush(new Product("Mouse", 5));
        var order = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var first = new CreateReservationRequest(firstProduct.getId(), order, 2);
        var second = new CreateReservationRequest(
                changedField.equals("product") ? secondProduct.getId() : firstProduct.getId(),
                order, changedField.equals("quantity") ? 3 : 2);
        List<Callable<ResponseEntity<JsonNode>>> tasks = List.of(
                () -> send(key, first), () -> send(key, second));

        var responses = race(tasks);

        assertThat(responses.stream().map(response -> response.getStatusCode().value()).toList())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(reservations.count()).isEqualTo(1);
        var winner = reservations.findAll().get(0);
        assertThat(products.findById(firstProduct.getId()).orElseThrow().getQuantity())
                .isEqualTo(winner.getProductId().equals(firstProduct.getId()) ? 5 - winner.getQuantity() : 5);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getQuantity())
                .isEqualTo(winner.getProductId().equals(secondProduct.getId()) ? 5 - winner.getQuantity() : 5);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void rollbackReleasesKeyAndRestoresStock() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var request = new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            idempotent.create(key, request);
            throw new IllegalStateException("Transaction aborted");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Transaction aborted");

        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(reservations.count()).isZero();
        assertThat(requestCount()).isZero();
        assertThat(send(key, request).getStatusCode().value()).isEqualTo(201);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);
    }

    private int requestCount() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_requests", Integer.class);
    }

    private ResponseEntity<JsonNode> send(String key, CreateReservationRequest request) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/reservations", new HttpEntity<>(request, headers), JsonNode.class);
    }

    private <T> List<T> race(List<Callable<T>> actions) throws Exception {
        var executor = Executors.newFixedThreadPool(actions.size());
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        List<Future<T>> pending = new ArrayList<>();
        try {
            for (var action : actions) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start signal timed out");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var result : pending) {
                results.add(result.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
