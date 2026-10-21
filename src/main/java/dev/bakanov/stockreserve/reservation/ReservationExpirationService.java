package dev.bakanov.stockreserve.reservation;

import dev.bakanov.stockreserve.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
public class ReservationExpirationService {

    private final ReservationRepository reservations;
    private final ProductRepository products;
    private final Clock clock;

    public ReservationExpirationService(ReservationRepository reservations, ProductRepository products, Clock clock) {
        this.reservations = reservations;
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public boolean expireNext() {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var reservation = reservations.findNextDueForUpdate(now).orElse(null);
        if (reservation == null) {
            return false;
        }
        var product = products.findByIdForUpdate(reservation.getProductId())
                .orElseThrow(() -> new IllegalStateException("Reservation product not found"));
        if (reservation.expire(now)) {
            product.release(reservation.getQuantity());
        }
        return true;
    }
}
