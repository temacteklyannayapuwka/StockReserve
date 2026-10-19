package dev.bakanov.stockreserve.product;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.password=")
@Transactional
class ProductPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ProductRepository products;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void savesProductAndLoadsItFromDatabase() {
        var saved = products.saveAndFlush(new Product("Keyboard", 5));
        var id = saved.getId();
        entityManager.clear();

        var loaded = products.findById(id).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getName()).isEqualTo("Keyboard");
        assertThat(loaded.getQuantity()).isEqualTo(5);
    }

    @Test
    void acceptsZeroStock() {
        var saved = products.saveAndFlush(new Product("Mouse", 0));
        entityManager.clear();

        assertThat(products.findById(saved.getId()).orElseThrow().getQuantity()).isZero();
    }

    @Test
    void databaseRejectsNegativeStock() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO products (id, name, quantity) VALUES (?, ?, ?)",
                UUID.randomUUID(), "Monitor", -1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("products_quantity_non_negative");
    }

    @Test
    void databaseRejectsBlankName() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO products (id, name, quantity) VALUES (?, ?, ?)",
                UUID.randomUUID(), "   ", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("products_name_not_blank");
    }

    @Test
    void healthIsUpWithDatabaseConnected() {
        var response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
