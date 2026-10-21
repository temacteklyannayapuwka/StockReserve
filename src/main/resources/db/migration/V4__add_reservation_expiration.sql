ALTER TABLE reservations ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE reservations SET expires_at = CURRENT_TIMESTAMP + INTERVAL '15 minutes';
ALTER TABLE reservations ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE reservations DROP CONSTRAINT reservations_status_valid;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_valid
    CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

CREATE INDEX reservations_expiration_idx ON reservations(expires_at, id)
    WHERE status = 'ACTIVE';
