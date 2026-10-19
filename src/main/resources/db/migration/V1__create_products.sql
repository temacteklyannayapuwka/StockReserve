CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT products_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT products_quantity_non_negative CHECK (quantity >= 0)
);
