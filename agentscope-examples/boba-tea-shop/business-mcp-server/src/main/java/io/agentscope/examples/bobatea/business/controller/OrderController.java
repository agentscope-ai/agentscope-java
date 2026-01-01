/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.examples.bobatea.business.controller;

import io.agentscope.examples.bobatea.business.entity.Order;
import io.agentscope.examples.bobatea.business.entity.Product;
import io.agentscope.examples.bobatea.business.model.ApiResponse;
import io.agentscope.examples.bobatea.business.model.OrderCreateRequest;
import io.agentscope.examples.bobatea.business.model.OrderQueryRequest;
import io.agentscope.examples.bobatea.business.model.OrderRemarkRequest;
import io.agentscope.examples.bobatea.business.model.OrderResponse;
import io.agentscope.examples.bobatea.business.model.ProductValidateResponse;
import io.agentscope.examples.bobatea.business.model.StockCheckResponse;
import io.agentscope.examples.bobatea.business.service.OrderService;
import io.agentscope.examples.bobatea.business.util.I18nUtil;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order REST Controller
 * Provides HTTP API interface
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired private OrderService orderService;

    /**
     * Create order
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody OrderCreateRequest request) {
        try {
            OrderResponse order = orderService.createOrder(request);
            return ResponseEntity.ok(ApiResponse.success(null, order));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("order.create.error", e.getMessage())));
        }
    }

    /**
     * Query order by user ID and order ID
     */
    @GetMapping("/{userId}/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable Long userId, @PathVariable String orderId) {
        OrderResponse order = orderService.getOrderByUserIdAndOrderId(userId, orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(null, order));
    }

    /**
     * Query order list by user ID
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getOrdersByUserId(@PathVariable Long userId) {
        List<OrderResponse> orders = orderService.getOrdersByUserId(userId);
        return ResponseEntity.ok(orders);
    }

    /**
     * Query user orders with pagination
     */
    @GetMapping("/user/{userId}/page")
    public ResponseEntity<Page<OrderResponse>> getOrdersByUserIdWithPagination(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OrderResponse> orders = orderService.getOrdersByUserIdWithPagination(userId, pageable);
        return ResponseEntity.ok(orders);
    }

    /**
     * Multi-dimensional query for user orders
     */
    @PostMapping("/query")
    public ResponseEntity<List<OrderResponse>> queryOrders(
            @Valid @RequestBody OrderQueryRequest request) {
        List<OrderResponse> orders = orderService.queryOrders(request);
        return ResponseEntity.ok(orders);
    }

    /**
     * Delete order
     */
    @DeleteMapping("/{userId}/{orderId}")
    public ResponseEntity<ApiResponse<Void>> deleteOrder(
            @PathVariable Long userId, @PathVariable String orderId) {
        try {
            boolean deleted = orderService.deleteOrder(userId, orderId);
            if (deleted) {
                return ResponseEntity.ok(
                        ApiResponse.success(I18nUtil.getMessage("order.delete.success")));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage("order.delete.error", e.getMessage())));
        }
    }

    /**
     * Update order remark
     */
    @PutMapping("/{userId}/{orderId}/remark")
    public ResponseEntity<ApiResponse<OrderResponse>> updateOrderRemark(
            @PathVariable Long userId,
            @PathVariable String orderId,
            @RequestBody OrderRemarkRequest request) {
        try {
            String remark = request.getRemark();
            OrderResponse order = orderService.updateOrderRemark(userId, orderId, remark);
            if (order != null) {
                return ResponseEntity.ok(ApiResponse.success(null, order));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(
                            ApiResponse.error(
                                    I18nUtil.getMessage(
                                            "order.remark.update.error", e.getMessage())));
        }
    }

    /**
     * Get all orders (compatible with original interface)
     */
    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders() {
        List<Order> orders = orderService.getAllOrders();
        return ResponseEntity.ok(orders);
    }

    /**
     * Query order by order ID (compatible with original interface)
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    /**
     * Check stock
     */
    @GetMapping("/stock/{productName}")
    public ResponseEntity<StockCheckResponse> checkStock(
            @PathVariable String productName, @RequestParam int quantity) {
        boolean available = orderService.checkStock(productName, quantity);
        return ResponseEntity.ok(new StockCheckResponse(productName, quantity, available));
    }

    /**
     * Get all available products
     */
    @GetMapping("/products")
    public ResponseEntity<List<Product>> getProducts() {
        List<Product> products = orderService.getAvailableProducts();
        return ResponseEntity.ok(products);
    }

    /**
     * Get product information by product name
     */
    @GetMapping("/products/{productName}")
    public ResponseEntity<Product> getProduct(@PathVariable String productName) {
        Product product = orderService.getProductByName(productName);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

    /**
     * Validate if product exists
     */
    @GetMapping("/products/{productName}/validate")
    public ResponseEntity<ProductValidateResponse> validateProduct(
            @PathVariable String productName) {
        boolean exists = orderService.validateProduct(productName);
        return ResponseEntity.ok(new ProductValidateResponse(productName, exists));
    }
}
