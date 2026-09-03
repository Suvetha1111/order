package com.orderflow.product.controller;

import com.orderflow.common.dto.ApiResponse;
import com.orderflow.common.dto.PagedResponse;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateInventoryRequest;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.service.InventoryService;
import com.orderflow.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final InventoryService inventoryService;

    public ProductController(ProductService productService,
                             InventoryService inventoryService) {
        this.productService = productService;
        this.inventoryService = inventoryService;
    }

    /**
     * GET /api/products — public, paginated product listing.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProductResponse>>> listProducts(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        PagedResponse<ProductResponse> products = productService.listProducts(pageable);
        return ResponseEntity.ok(ApiResponse.success(products));
    }

    /**
     * GET /api/products/{id} — public, single product detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable UUID id) {
        ProductResponse product = productService.getProduct(id);
        return ResponseEntity.ok(ApiResponse.success(product));
    }

    /**
     * POST /api/products — admin-only, create product with initial inventory.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse product = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(product, "Product created"));
    }

    /**
     * PUT /api/products/{id} — admin-only, update product details.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductResponse product = productService.updateProduct(id, request);
        return ResponseEntity.ok(ApiResponse.success(product, "Product updated"));
    }

    /**
     * PUT /api/products/{id}/inventory — admin-only, set inventory quantity.
     */
    @PutMapping("/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> updateInventory(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInventoryRequest request) {
        inventoryService.updateInventory(id, request);
        ProductResponse product = productService.getProduct(id);
        return ResponseEntity.ok(ApiResponse.success(product, "Inventory updated"));
    }
}
