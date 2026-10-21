package dev.bakanov.stockreserve.reservation;

import dev.bakanov.stockreserve.product.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ReservationService {

    private final ProductRepository products;
    private final ReservationRepository reservations;

    private final Clock clock;
    private final Duration ttl;

    public ReservationService(ProductRepository products, ReservationRepository reservations,
                              Clock clock, @Value("${reservation.ttl:PT15M}") Duration ttl) {
        if (ttl.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Reservation TTL must be at least one millisecond");
        }
        this.products = products;
        this.reservations = reservations;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        var product = products.findByIdForUpdate(request.productId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!product.reserve(request.quantity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock");
        }
        var expiresAt = clock.instant().plus(ttl).truncatedTo(ChronoUnit.MICROS);
        var reservation = reservations.save(
                new Reservation(product.getId(), request.orderId(), request.quantity(), expiresAt));
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse confirm(UUID id) {
        var reservation = findByIdForUpdate(id);
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return ReservationResponse.from(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.EXPIRED || reservation.isDue(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expired reservation cannot be confirmed");
        }
        if (!reservation.confirm()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled reservation cannot be confirmed");
        }
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse cancel(UUID id) {
        var reservation = findByIdForUpdate(id);
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return ReservationResponse.from(reservation);
        }
        if (!reservation.cancel()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation cannot be cancelled in its current state");
        }
        var product = products.findByIdForUpdate(reservation.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        product.release(reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }

    private Reservation findByIdForUpdate(UUID id) {
        return reservations.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID id) {
        return reservations.findById(id)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }
}
