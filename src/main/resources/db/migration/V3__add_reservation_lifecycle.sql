ALTER TABLE reservations DROP CONSTRAINT reservations_status_valid;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_valid
    CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED'));
