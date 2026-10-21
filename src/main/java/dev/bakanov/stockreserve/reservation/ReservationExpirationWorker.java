package dev.bakanov.stockreserve.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "reservation.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationWorker {

    private final ReservationExpirationService expiration;

    public ReservationExpirationWorker(ReservationExpirationService expiration) {
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${reservation.expiration.poll-interval-ms:1000}")
    public void expireReservations() {
        for (int i = 0; i < 100; i++) {
            if (!expiration.expireNext()) {
                break;
            }
        }
    }
}
