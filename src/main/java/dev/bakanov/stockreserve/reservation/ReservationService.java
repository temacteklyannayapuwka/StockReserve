package dev.bakanov.stockreserve.reservation;

import dev.bakanov.stockreserve.product.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ReservationService {

    private final ProductRepository products;
    private final ReservationRepository reservations;

    public ReservationService(ProductRepository products, ReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        var product = products.findByIdForUpdate(request.productId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        if (!product.reserve(request.quantity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock");
        }
        var reservation = reservations.save(
                new Reservation(product.getId(), request.orderId(), request.quantity()));
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID id) {
        return reservations.findById(id)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }
}
