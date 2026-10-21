package dev.bakanov.stockreserve.reservation;

import dev.bakanov.stockreserve.product.Product;
import dev.bakanov.stockreserve.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {"spring.datasource.password=", "reservation.ttl=PT1S",
        "reservation.expiration.poll-interval-ms=50"})
class ReservationExpirationWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ProductRepository products;

    @Autowired
    ReservationService reservations;

    @Test
    void schedulerExpiresReservationWithoutManualInvocation() {
        var product = products.saveAndFlush(new Product("Keyboard", 5));
        var reservation = reservations.create(
                new CreateReservationRequest(product.getId(), UUID.randomUUID(), 2));

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            assertThat(reservations.findById(reservation.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(products.findById(product.getId()).orElseThrow().getQuantity()).isEqualTo(5);
        });
    }
}
