package dev.bakanov.stockreserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedApplicationIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private Process application;
    private URI baseUri;

    @BeforeAll
    void startApplication() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        baseUri = URI.create("http://localhost:" + port);
        var builder = new ProcessBuilder(
                "java", "-jar", Path.of(System.getProperty("jar.path")).toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("DB_URL", postgres.getJdbcUrl());
        builder.environment().put("DB_USER", postgres.getUsername());
        builder.environment().put("DB_PASSWORD", postgres.getPassword());
        builder.environment().put("SERVER_PORT", Integer.toString(port));
        application = builder.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && application.isAlive()) {
            try {
                var response = send("GET", "/actuator/health", null, null);
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(250);
        }
        assertThat(application.isAlive()).as("packaged application should stay running").isTrue();
        throw new IllegalStateException("Packaged application did not become ready");
    }

    @AfterAll
    void stopApplication() throws Exception {
        if (application != null && application.isAlive()) {
            application.destroy();
            if (!application.waitFor(10, TimeUnit.SECONDS)) {
                application.destroyForcibly();
                application.waitFor();
            }
        }
    }

    @Test
    void packagedJarServesHealthAndDeduplicatesReservationRequests() throws Exception {
        var productResponse = send("POST", "/api/products",
                "{\"name\":\"Packaged item\",\"quantity\":5}", null);
        assertThat(productResponse.statusCode()).isEqualTo(201);
        JsonNode product = json.readTree(productResponse.body());
        String productId = product.get("id").asText();

        String requestBody = "{\"productId\":\"" + productId
                + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"quantity\":2}";
        String key = UUID.randomUUID().toString();
        var first = send("POST", "/api/reservations", requestBody, key);
        var repeated = send("POST", "/api/reservations", requestBody, key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(repeated.statusCode()).isEqualTo(201);
        assertThat(json.readTree(repeated.body()).get("id").asText())
                .isEqualTo(json.readTree(first.body()).get("id").asText());
        assertThat(repeated.headers().firstValue(HttpHeaders.LOCATION))
                .isEqualTo(first.headers().firstValue(HttpHeaders.LOCATION));

        var updatedProduct = send("GET", "/api/products/" + productId, null, null);
        assertThat(json.readTree(updatedProduct.body()).get("quantity").asInt()).isEqualTo(3);
    }

    private HttpResponse<String> send(String method, String path, String body, String key) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
