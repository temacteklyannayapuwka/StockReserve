package dev.bakanov.stockreserve.product;

import java.util.UUID;

public record ProductResponse(UUID id, String name, int quantity) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getQuantity());
    }
}
