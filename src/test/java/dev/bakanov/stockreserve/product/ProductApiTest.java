package dev.bakanov.stockreserve.product;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.password=")
class ProductApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ProductRepository products;

    @BeforeEach
    void clearProducts() {
        products.deleteAll();
    }

    @Test
    void createsProductAndReadsItUsingLocation() {
        var created = http.postForEntity("/api/products",
                json("{\"name\":\"  Keyboard  \",\"quantity\":5}"), ProductResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var product = created.getBody();
        assertThat(product).isNotNull();
        assertThat(product.id()).isNotNull();
        assertThat(product.name()).isEqualTo("Keyboard");
        assertThat(product.quantity()).isEqualTo(5);
        assertThat(created.getHeaders().getLocation()).isEqualTo(URI.create("/api/products/" + product.id()));

        var loaded = http.getForEntity(created.getHeaders().getLocation().toString(), ProductResponse.class);

        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isEqualTo(product);
        assertThat(products.findById(product.id()).orElseThrow().getQuantity()).isEqualTo(5);
    }

    @Test
    void createsProductWithZeroStock() {
        var response = http.postForEntity("/api/products",
                json("{\"name\":\"Mouse\",\"quantity\":0}"), ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().quantity()).isZero();
    }

    @Test
    void acceptsMaximumNameLengthAndStock() {
        var response = http.postForEntity("/api/products",
                json("{\"name\":\"" + "a".repeat(120) + "\",\"quantity\":2147483647}"), ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).hasSize(120);
        assertThat(response.getBody().quantity()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void returnsNotFoundForUnknownProduct() {
        var response = http.getForEntity("/api/products/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(404);
        assertThat(response.getBody().path("detail").asText()).isEqualTo("Product not found");
    }

    @Test
    void returnsBadRequestForMalformedId() {
        var response = http.getForEntity("/api/products/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestWithoutSavingProduct(String request) {
        var response = http.postForEntity("/api/products", json(request), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
        assertThat(products.count()).isZero();
    }

    static Stream<String> invalidRequests() {
        return Stream.of(
                "{\"name\":\"\",\"quantity\":1}",
                "{\"name\":\"   \",\"quantity\":1}",
                "{\"name\":\"\\t\\n\",\"quantity\":1}",
                "{\"name\":null,\"quantity\":1}",
                "{\"quantity\":1}",
                "{\"name\":\"" + "a".repeat(121) + "\",\"quantity\":1}",
                "{\"name\":\"Keyboard\",\"quantity\":-1}",
                "{\"name\":\"Keyboard\",\"quantity\":null}",
                "{\"name\":\"Keyboard\"}",
                "{\"name\":\"Keyboard\",\"quantity\":1.5}",
                "{\"name\":\"Keyboard\",\"quantity\":2147483648}",
                "{\"name\":\"Keyboard\",\"quantity\":\"many\"}",
                "{}",
                "null",
                "{",
                "");
    }

    private HttpEntity<String> json(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
