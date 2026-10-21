package dev.bakanov.stockreserve.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Reservation() {
    }

    public Reservation(UUID productId, UUID orderId, int quantity, Instant expiresAt) {
        this.productId = productId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public boolean confirm() {
        if (status != ReservationStatus.ACTIVE) {
            return false;
        }
        status = ReservationStatus.CONFIRMED;
        return true;
    }

    public boolean cancel() {
        if (status != ReservationStatus.ACTIVE) {
            return false;
        }
        status = ReservationStatus.CANCELLED;
        return true;
    }

    public boolean isDue(Instant now) {
        return status == ReservationStatus.ACTIVE && !expiresAt.isAfter(now);
    }

    public boolean expire(Instant now) {
        if (!isDue(now)) {
            return false;
        }
        status = ReservationStatus.EXPIRED;
        return true;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }
}
