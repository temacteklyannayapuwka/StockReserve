package dev.bakanov.stockreserve.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bakanov.stockreserve.product.Product;
import dev.bakanov.stockreserve.product.ProductRepository;
import dev.bakanov.stockreserve.product.ProductResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.password=")
class ReservationApiTest {

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
    PlatformTransactionManager transactions;

    @Autowired
    EntityManager entityManager;

    @BeforeEach
    void clearData() {
        reservations.deleteAll();
        products.deleteAll();
    }

    @Test
    void createsReservationAndReducesAvailableStock() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var orderId = UUID.randomUUID();
        var request = new CreateReservationRequest(product.getId(), orderId, 2);

        var created = http.postForEntity("/api/reservations", request, ReservationResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var reservation = created.getBody();
        assertThat(reservation).isNotNull();
        assertThat(reservation.id()).isNotNull();
        assertThat(reservation.productId()).isEqualTo(product.getId());
        assertThat(reservation.orderId()).isEqualTo(orderId);
        assertThat(reservation.quantity()).isEqualTo(2);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(created.getHeaders().getLocation())
                .isEqualTo(URI.create("/api/reservations/" + reservation.id()));

        var loaded = http.getForEntity(created.getHeaders().getLocation().toString(), ReservationResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isEqualTo(reservation);
        var remaining = http.getForEntity("/api/products/" + product.getId(), ProductResponse.class);
        assertThat(remaining.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(remaining.getBody()).isNotNull();
        assertThat(remaining.getBody().quantity()).isEqualTo(3);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void reservesEntireStock() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));

        var response = http.postForEntity("/api/reservations",
                new CreateReservationRequest(product.getId(), UUID.randomUUID(), 5), ReservationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rejectsInsufficientStockWithoutChangingData(int stock) {
        var product = products.saveAndFlush(new Product("Keyboard", stock));

        var response = http.postForEntity("/api/reservations",
                new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("detail").asText()).isEqualTo("Insufficient stock");
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(stock);
        assertThat(reservations.count()).isZero();
    }

    @Test
    void rejectsUnknownProduct() {
        var response = http.postForEntity("/api/reservations",
                new CreateReservationRequest(UUID.randomUUID(), UUID.randomUUID(), 1), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("detail").asText()).isEqualTo("Product not found");
        assertThat(reservations.count()).isZero();
    }

    @Test
    void returnsNotFoundForUnknownReservation() {
        var response = http.getForEntity("/api/reservations/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("detail").asText()).isEqualTo("Reservation not found");
    }

    @Test
    void rejectsMalformedReservationId() {
        var response = http.getForEntity("/api/reservations/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestWithoutReservingStock(String body) {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(body.replace("PRODUCT_ID", product.getId().toString()), headers);

        var response = http.postForEntity("/api/reservations", request, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(reservations.count()).isZero();
    }

    @Test
    void rollbackRestoresStockAndRemovesReservation() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
            entityManager.flush();
            throw new IllegalStateException("Transaction aborted");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Transaction aborted");

        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(reservations.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void concurrentRequestsCannotReserveMoreThanAvailable(int amount) throws Exception {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var executor = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> pending = new ArrayList<>();

        try {
            for (int i = 0; i < 10; i++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start signal timed out");
                    }
                    return http.postForEntity("/api/reservations",
                            new CreateReservationRequest(product.getId(), UUID.randomUUID(), amount),
                            JsonNode.class);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (var result : pending) {
                statuses.add(HttpStatus.valueOf(result.get(20, TimeUnit.SECONDS).getStatusCode().value()));
            }

            int expected = 5 / amount;
            assertThat(statuses).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(expected);
            assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT).count()).isEqualTo(10 - expected);
            assertThat(products.findById(product.getId()).orElseThrow().getQuantity())
                    .isEqualTo(5 % amount);
            assertThat(reservations.count()).isEqualTo(expected);
            assertThat(reservations.findAll()).extracting(Reservation::getQuantity).containsOnly(amount);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    static Stream<String> invalidRequests() {
        String fields = "\"productId\":\"PRODUCT_ID\",\"orderId\":\"00000000-0000-0000-0000-000000000001\"";
        return Stream.of(
                "{" + fields + ",\"quantity\":0}",
                "{" + fields + ",\"quantity\":-1}",
                "{" + fields + ",\"quantity\":null}",
                "{" + fields + "}",
                "{" + fields + ",\"quantity\":1.5}",
                "{" + fields + ",\"quantity\":2147483648}",
                "{\"productId\":null,\"orderId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}",
                "{\"productId\":\"broken\",\"orderId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}",
                "{\"productId\":\"PRODUCT_ID\",\"quantity\":1}",
                "{\"productId\":\"PRODUCT_ID\",\"orderId\":null,\"quantity\":1}",
                "{\"productId\":\"PRODUCT_ID\",\"orderId\":\"broken\",\"quantity\":1}",
                "{}",
                "null");
    }
}
