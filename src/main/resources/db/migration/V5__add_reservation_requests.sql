CREATE TABLE reservation_requests (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    order_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reservation_id UUID REFERENCES reservations(id)
);

CREATE INDEX reservation_requests_reservation_id_idx ON reservation_requests(reservation_id);
