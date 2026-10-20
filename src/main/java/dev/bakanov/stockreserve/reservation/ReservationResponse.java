package dev.bakanov.stockreserve.reservation;

import java.util.UUID;

public record ReservationResponse(
        UUID id, UUID productId, UUID orderId, int quantity, ReservationStatus status) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getProductId(),
                reservation.getOrderId(), reservation.getQuantity(), reservation.getStatus());
    }
}
