CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id),
    order_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservations_status_valid CHECK (status IN ('ACTIVE'))
);

CREATE INDEX reservations_product_id_idx ON reservations(product_id);
