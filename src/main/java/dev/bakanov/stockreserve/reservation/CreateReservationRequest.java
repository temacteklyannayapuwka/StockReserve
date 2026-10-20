package dev.bakanov.stockreserve.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateReservationRequest(
        @NotNull UUID productId,
        @NotNull UUID orderId,
        @NotNull @Positive Integer quantity) {
}
