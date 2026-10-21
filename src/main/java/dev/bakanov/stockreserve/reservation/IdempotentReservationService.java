package dev.bakanov.stockreserve.reservation;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class IdempotentReservationService {

    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final EntityManager entityManager;

    public IdempotentReservationService(JdbcTemplate jdbc, ReservationService reservations, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.entityManager = entityManager;
    }

    @Transactional
    public ReservationResponse create(String key, CreateReservationRequest request) {
        var requestId = parseKey(key);
        jdbc.update("""
                INSERT INTO reservation_requests (id, product_id, order_id, quantity)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, requestId, request.productId(), request.orderId(), request.quantity());
        var stored = jdbc.queryForObject("""
                SELECT product_id, order_id, quantity, reservation_id
                FROM reservation_requests WHERE id = ? FOR UPDATE
                """, (row, index) -> new StoredRequest(
                row.getObject("product_id", UUID.class), row.getObject("order_id", UUID.class),
                row.getInt("quantity"), row.getObject("reservation_id", UUID.class)), requestId);
        if (stored == null) {
            throw new IllegalStateException("Reservation request not found");
        }
        if (!stored.productId().equals(request.productId())
                || !stored.orderId().equals(request.orderId())
                || stored.quantity() != request.quantity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with different request data");
        }
        if (stored.reservationId() != null) {
            return reservations.findById(stored.reservationId());
        }
        var reservation = reservations.create(request);
        entityManager.flush();
        jdbc.update("UPDATE reservation_requests SET reservation_id = ? WHERE id = ?",
                reservation.id(), requestId);
        return reservation;
    }

    private UUID parseKey(String key) {
        try {
            var id = UUID.fromString(key);
            if (!id.toString().equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID");
        }
    }

    private record StoredRequest(UUID productId, UUID orderId, int quantity, UUID reservationId) {
    }
}
