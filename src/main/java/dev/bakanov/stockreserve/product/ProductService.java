package dev.bakanov.stockreserve.product;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        var product = products.save(new Product(request.name().strip(), request.quantity()));
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(UUID id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}
