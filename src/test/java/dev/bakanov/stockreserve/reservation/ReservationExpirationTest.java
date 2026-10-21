package dev.bakanov.stockreserve.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bakanov.stockreserve.product.Product;
import dev.bakanov.stockreserve.product.ProductRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Import(ReservationExpirationTest.TestTimeConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.password=", "reservation.expiration.enabled=false"})
class ReservationExpirationTest {

    static final Instant START = Instant.parse("2026-10-21T10:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MutableClock clock;

    @Autowired
    ProductRepository products;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    ReservationService service;

    @Autowired
    ReservationExpirationService expiration;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void reset() {
        reservations.deleteAll();
        products.deleteAll();
        clock.set(START);
    }

    @Test
    void deadlineIsStoredAndReturnedByApi() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var response = http.postForEntity("/api/reservations",
                new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2),
                ReservationResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        var reservation = response.getBody();
        assertThat(reservation.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(service.findById(reservation.id())).isEqualTo(reservation);
        assertThat(reservations.findById(reservation.id()).orElseThrow().getExpiresAt())
                .isEqualTo(reservation.expiresAt());
    }

    @Test
    void expiresExactlyAtDeadlineAndReturnsStockOnlyOnce() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt().minusNanos(1));

        assertThat(expiration.expireNext()).isFalse();
        assertThat(service.findById(reservation.id()).status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);

        clock.set(reservation.expiresAt());
        assertThat(expiration.expireNext()).isTrue();
        assertThat(expiration.expireNext()).isFalse();
        var loaded = http.getForEntity("/api/reservations/" + reservation.id(), ReservationResponse.class);
        assertThat(loaded.getBody()).isNotNull();
        assertThat(loaded.getBody().status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        for (String action : List.of("confirm", "cancel")) {
            assertThat(http.postForEntity("/api/reservations/" + reservation.id() + "/" + action,
                    null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
        }
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void cannotConfirmDueReservationBeforeWorkerRuns() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt());

        var response = http.postForEntity("/api/reservations/" + reservation.id() + "/confirm",
                null, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(service.findById(reservation.id()).status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(expiration.expireNext()).isTrue();
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel"})
    void terminalReservationsAreNotExpired(String action) {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        var finished = http.postForEntity("/api/reservations/" + reservation.id() + "/" + action,
                null, ReservationResponse.class);
        assertThat(finished.getStatusCode().value()).isEqualTo(200);
        clock.set(reservation.expiresAt().plusSeconds(1));

        assertThat(expiration.expireNext()).isFalse();
        assertThat(service.findById(reservation.id())).isEqualTo(finished.getBody());
        assertThat(http.postForEntity("/api/reservations/" + reservation.id() + "/" + action,
                null, ReservationResponse.class).getStatusCode().value()).isEqualTo(200);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity())
                .isEqualTo(action.equals("cancel") ? 5 : 3);
    }

    @Test
    void rollbackRestoresActiveReservationAndReservedStock() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt());

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(expiration.expireNext()).isTrue();
            entityManager.flush();
            throw new IllegalStateException("Transaction aborted");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Transaction aborted");

        assertThat(service.findById(reservation.id()).status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(expiration.expireNext()).isTrue();
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void expiresMultipleReservationsWithoutLosingReturnedStock() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        var last = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 3));
        clock.set(last.expiresAt());

        assertThat(expiration.expireNext()).isTrue();
        assertThat(expiration.expireNext()).isTrue();
        assertThat(expiration.expireNext()).isFalse();

        assertThat(reservations.findAll()).extracting(Reservation::getStatus)
                .containsOnly(ReservationStatus.EXPIRED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void concurrentWorkersReturnStockOnlyOnce() throws Exception {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt());
        List<Callable<Boolean>> tasks = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<Boolean>) expiration::expireNext).toList();

        assertThat(race(tasks)).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(service.findById(reservation.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void concurrentConfirmationCannotReviveExpiredReservation() throws Exception {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt());
        List<Callable<Integer>> tasks = List.of(
                () -> expiration.expireNext() ? 200 : 204,
                () -> http.postForEntity("/api/reservations/" + reservation.id() + "/confirm",
                        null, JsonNode.class).getStatusCode().value());

        assertThat(race(tasks)).containsExactly(200, 409);
        assertThat(service.findById(reservation.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void cancellationAndExpirationCannotBothReturnStock() throws Exception {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        clock.set(reservation.expiresAt());
        List<Callable<Boolean>> tasks = List.of(
                expiration::expireNext,
                () -> http.postForEntity("/api/reservations/" + reservation.id() + "/cancel",
                        null, JsonNode.class).getStatusCode().value() == 200);

        assertThat(race(tasks)).containsExactlyInAnyOrder(true, false);
        assertThat(service.findById(reservation.id()).status())
                .isIn(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);
        assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void lockedReservationDoesNotBlockExpirationOfAnother() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var first = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));
        var second = service.create(new CreateReservationRequest(product.getId(), UUID.randomUUID(), 3));
        clock.set(second.expiresAt());
        var executor = Executors.newSingleThreadExecutor();

        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                reservations.findByIdForUpdate(first.id()).orElseThrow();
                assertThatCode(() -> assertThat(executor.submit(expiration::expireNext)
                        .get(10, TimeUnit.SECONDS)).isTrue()).doesNotThrowAnyException();
            });
            assertThat(service.findById(first.id()).status()).isEqualTo(ReservationStatus.ACTIVE);
            assertThat(service.findById(second.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(expiration.expireNext()).isTrue();
            assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void migrationPreservesExistingReservationsAndAssignsDeadlines() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("expiration_upgrade").defaultSchema("expiration_upgrade").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            var productId = UUID.randomUUID();
            statement.execute("INSERT INTO expiration_upgrade.products (id, name, quantity) VALUES ('"
                    + productId + "', 'Legacy', 4)");
            for (String status : List.of("ACTIVE", "CONFIRMED", "CANCELLED")) {
                statement.execute("INSERT INTO expiration_upgrade.reservations "
                        + "(id, product_id, order_id, quantity, status) VALUES ('" + UUID.randomUUID()
                        + "', '" + productId + "', '" + UUID.randomUUID() + "', 1, '" + status + "')");
            }
            var before = Instant.now().plus(Duration.ofMinutes(15)).minusSeconds(1);
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("expiration_upgrade").defaultSchema("expiration_upgrade").load().migrate();
            var after = Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(1);

            try (var rows = statement.executeQuery(
                    "SELECT status, expires_at FROM expiration_upgrade.reservations ORDER BY status")) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString("status"));
                    assertThat(rows.getTimestamp("expires_at").toInstant()).isBetween(before, after);
                }
                assertThat(statuses).containsExactly("ACTIVE", "CANCELLED", "CONFIRMED");
            }
            try (var rows = statement.executeQuery("SELECT quantity FROM expiration_upgrade.products")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("quantity")).isEqualTo(4);
            }
        }
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

    @TestConfiguration
    static class TestTimeConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(new AtomicReference<>(START), ZoneOffset.UTC);
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
