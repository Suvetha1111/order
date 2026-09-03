package com.orderflow.product.service;

import com.orderflow.common.dto.PagedResponse;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.entity.Inventory;
import com.orderflow.product.entity.Product;
import com.orderflow.product.repository.InventoryRepository;
import com.orderflow.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public ProductService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Create a new product with initial inventory.
     * Both are saved in one transaction — no product exists without inventory.
     */
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new IllegalArgumentException("Product with SKU '%s' already exists".formatted(request.sku()));
        }

        Product product = new Product();
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setSku(request.sku().trim().toUpperCase());
        product = productRepository.save(product);

        Inventory inventory = new Inventory(product, request.initialStock());
        inventory = inventoryRepository.save(inventory);

        log.info("Product created: productId={}, sku={}", product.getId(), product.getSku());

        return ProductResponse.from(product, inventory);
    }

    /**
     * Get a single product by ID.
     * Cached in Redis with cache-aside pattern.
     * Cache key: "products::<productId>"
     */
    @Cacheable(value = "products", key = "#productId")
    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product not found: " + productId));

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElse(null);

        return ProductResponse.from(product, inventory);
    }

    /**
     * List active products with pagination.
     * Not cached — pagination makes cache keys unpredictable,
     * and listing queries are already efficient with proper indexes.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> listProducts(Pageable pageable) {
        Page<ProductResponse> page = productRepository.findByActiveTrue(pageable)
                .map(product -> {
                    Inventory inventory = inventoryRepository.findByProductId(product.getId())
                            .orElse(null);
                    return ProductResponse.from(product, inventory);
                });

        return PagedResponse.from(page);
    }

    /**
     * Update product details. Evicts the product from cache.
     */
    @CacheEvict(value = "products", key = "#productId")
    @Transactional
    public ProductResponse updateProduct(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product not found: " + productId));

        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }

        product = productRepository.save(product);

        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);

        log.info("Product updated: productId={}", productId);

        return ProductResponse.from(product, inventory);
    }
}
