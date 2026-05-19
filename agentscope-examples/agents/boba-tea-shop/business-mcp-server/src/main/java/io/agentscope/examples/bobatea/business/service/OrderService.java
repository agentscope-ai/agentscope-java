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

package io.agentscope.examples.bobatea.business.service;

import io.agentscope.examples.bobatea.business.entity.Order;
import io.agentscope.examples.bobatea.business.entity.Product;
import io.agentscope.examples.bobatea.business.entity.User;
import io.agentscope.examples.bobatea.business.mapper.OrderMapper;
import io.agentscope.examples.bobatea.business.mapper.ProductMapper;
import io.agentscope.examples.bobatea.business.mapper.UserMapper;
import io.agentscope.examples.bobatea.business.model.OrderCreateRequest;
import io.agentscope.examples.bobatea.business.model.OrderQueryRequest;
import io.agentscope.examples.bobatea.business.model.OrderResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order Service Class
 * Provides business logic related to orders
 */
@Service
@Transactional
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired private OrderMapper orderMapper;

    @Autowired private ProductMapper productMapper;

    @Autowired private UserMapper userMapper;

    /**
     * Validates if user exists, throws exception if not
     */
    public User validateUser(Long userId) {
        logger.info("=== OrderService.validateUser Entry ===");
        logger.info("Request parameter - userId: {}", userId);

        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID cannot be empty, please provide a valid user ID");
        }

        try {
            // Check if user exists
            User existingUser = userMapper.selectById(userId);
            if (existingUser == null) {
                throw new IllegalArgumentException(
                        "User does not exist, user ID: " + userId + ", please register first");
            }

            logger.info("=== OrderService.validateUser Exit ===");
            logger.info(
                    "Return result - User validation successful: {}", existingUser.getUsername());

            return existingUser;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.error("User validation exception", e);
            throw new RuntimeException("User validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create order (compatible with original MCP interface)
     */
    public Order createOrder(String productName, String sweetness, String iceLevel, int quantity) {
        logger.info("=== OrderService.createOrder Entry ===");
        logger.info(
                "Request parameters - productName: {}, sweetness: {}, iceLevel: {}, quantity: {}",
                productName,
                sweetness,
                iceLevel,
                quantity);

        try {
            // Convert sweetness and ice level to numbers
            Integer sweetnessLevel = convertSweetnessToNumber(sweetness);
            Integer iceLevelNumber = convertIceLevelToNumber(iceLevel);

            // Query product information from database
            Product product = productMapper.selectByNameAndStatus(productName, 1);
            if (product == null) {
                throw new IllegalArgumentException(
                        "Product does not exist or is unavailable: " + productName);
            }

            // Check stock
            if (product.getStock() < quantity) {
                String errorMsg =
                        String.format(
                                "Insufficient stock, product: %s, current stock: %d, required"
                                        + " quantity: %d",
                                productName, product.getStock(), quantity);
                logger.error("Failed to create order: {}", errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(quantity));
            String orderId = "ORDER_" + System.currentTimeMillis();

            // Create order entity
            Order order =
                    new Order(
                            orderId,
                            null,
                            product.getId(),
                            productName,
                            sweetnessLevel,
                            iceLevelNumber,
                            quantity,
                            unitPrice,
                            totalPrice,
                            null);
            order.onCreate();

            // Save to database
            orderMapper.insert(order);

            // Update product stock
            product.setStock(product.getStock() - quantity);
            product.onUpdate();
            productMapper.updateById(product);

            logger.info("=== OrderService.createOrder Exit ===");
            logger.info(
                    "Return result - orderId: {}, productName: {}, sweetness: {}, iceLevel: {},"
                            + " quantity: {}, price: {}",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetness(),
                    order.getIceLevel(),
                    order.getQuantity(),
                    order.getTotalPrice());

            return order;
        } catch (Exception e) {
            logger.error("Create order exception", e);
            throw e;
        }
    }

    /**
     * Create order (new interface)
     */
    public OrderResponse createOrder(OrderCreateRequest request) {
        logger.info("=== OrderService.createOrder Entry ===");
        logger.info("Request parameters - {}", request);

        try {
            // Validate if user exists, throw exception if not
            User user = validateUser(request.getUserId());

            // Query product information from database
            Product product = productMapper.selectByNameAndStatus(request.getProductName(), 1);
            if (product == null) {
                throw new IllegalArgumentException(
                        "Product does not exist or is unavailable: " + request.getProductName());
            }

            // Check stock
            if (product.getStock() < request.getQuantity()) {
                String errorMsg =
                        String.format(
                                "Insufficient stock, product: %s, current stock: %d, required"
                                        + " quantity: %d",
                                request.getProductName(),
                                product.getStock(),
                                request.getQuantity());
                logger.error("Failed to create order: {}", errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(request.getQuantity()));
            String orderId = "ORDER_" + System.currentTimeMillis();

            // Create order entity
            Order order =
                    new Order(
                            orderId,
                            request.getUserId(),
                            product.getId(),
                            request.getProductName(),
                            request.getSweetness(),
                            request.getIceLevel(),
                            request.getQuantity(),
                            unitPrice,
                            totalPrice,
                            request.getRemark());
            order.onCreate();

            // Save to database
            orderMapper.insert(order);

            // Update product stock
            product.setStock(product.getStock() - request.getQuantity());
            product.onUpdate();
            productMapper.updateById(product);

            logger.info("=== OrderService.createOrder Exit ===");
            logger.info("Return result - orderId: {}", order.getOrderId());

            return new OrderResponse(order);
        } catch (Exception e) {
            logger.error("Create order exception", e);
            throw e;
        }
    }

    /**
     * Query order (compatible with original MCP interface)
     */
    public Order getOrder(String orderId) {
        logger.info("=== OrderService.getOrder Entry ===");
        logger.info("Request parameter - orderId: {}", orderId);

        Order order = orderMapper.selectByOrderId(orderId);

        logger.info("=== OrderService.getOrder Exit ===");
        if (order != null) {
            logger.info(
                    "Return result - orderId: {}, productName: {}, sweetness: {}, iceLevel: {},"
                            + " quantity: {}, price: {}, createTime: {}",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetness(),
                    order.getIceLevel(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt());
        } else {
            logger.info("Return result - Order does not exist");
        }

        return order;
    }

    /**
     * Query order by user ID and order ID
     */
    public OrderResponse getOrderByUserIdAndOrderId(Long userId, String orderId) {
        logger.info("=== OrderService.getOrderByUserIdAndOrderId Entry ===");
        logger.info("Request parameters - userId: {}, orderId: {}", userId, orderId);

        try {
            // Validate if user exists, throw exception if not
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                OrderResponse response = new OrderResponse(order);
                logger.info("=== OrderService.getOrderByUserIdAndOrderId Exit ===");
                logger.info("Return result - orderId: {}", response.getOrderId());
                return response;
            } else {
                logger.info("=== OrderService.getOrderByUserIdAndOrderId Exit ===");
                logger.info("Return result - Order does not exist");
                return null;
            }
        } catch (Exception e) {
            logger.error("Query order exception", e);
            throw e;
        }
    }

    /**
     * Get all orders (compatible with original MCP interface)
     */
    public List<Order> getAllOrders() {
        logger.info("=== OrderService.getAllOrders Entry ===");

        List<Order> allOrders = orderMapper.selectAll();

        logger.info("=== OrderService.getAllOrders Exit ===");
        logger.info("Return result - Total orders: {}", allOrders.size());

        return allOrders;
    }

    /**
     * Query order list by user ID
     */
    public List<OrderResponse> getOrdersByUserId(Long userId) {
        logger.info("=== OrderService.getOrdersByUserId Entry ===");
        logger.info("Request parameter - userId: {}", userId);

        try {
            // Validate if user exists, throw exception if not
            validateUser(userId);

            List<Order> orders = orderMapper.selectByUserId(userId);
            List<OrderResponse> responses =
                    orders.stream().map(OrderResponse::new).collect(Collectors.toList());

            logger.info("=== OrderService.getOrdersByUserId Exit ===");
            logger.info("Return result - Total orders: {}", responses.size());

            return responses;
        } catch (Exception e) {
            logger.error("Query user orders exception", e);
            throw e;
        }
    }

    /**
     * Multi-dimensional query for user orders
     */
    public List<OrderResponse> queryOrders(OrderQueryRequest request) {
        logger.info("=== OrderService.queryOrders Entry ===");
        logger.info("Request parameters - {}", request);

        try {
            // Validate if user exists, throw exception if not
            validateUser(request.getUserId());

            List<Order> orders =
                    orderMapper.selectByUserIdAndConditions(
                            request.getUserId(),
                            request.getProductName(),
                            request.getSweetness(),
                            request.getIceLevel(),
                            request.getStartTime(),
                            request.getEndTime());

            List<OrderResponse> responses =
                    orders.stream().map(OrderResponse::new).collect(Collectors.toList());

            logger.info("=== OrderService.queryOrders Exit ===");
            logger.info("Return result - Total orders: {}", responses.size());

            return responses;
        } catch (Exception e) {
            logger.error("Query orders exception", e);
            throw e;
        }
    }

    /**
     * Query user orders with pagination
     */
    public Page<OrderResponse> getOrdersByUserIdWithPagination(Long userId, Pageable pageable) {
        logger.info("=== OrderService.getOrdersByUserIdWithPagination Entry ===");
        logger.info(
                "Request parameters - userId: {}, page: {}, size: {}",
                userId,
                pageable.getPageNumber(),
                pageable.getPageSize());

        try {
            // Validate if user exists, throw exception if not
            validateUser(userId);

            int offset = (int) pageable.getOffset();
            int size = pageable.getPageSize();
            List<Order> orders = orderMapper.selectByUserIdWithPagination(userId, offset, size);
            long total = orderMapper.countByUserId(userId);
            Page<Order> orderPage = new PageImpl<>(orders, pageable, total);
            Page<OrderResponse> responsePage = orderPage.map(OrderResponse::new);

            logger.info("=== OrderService.getOrdersByUserIdWithPagination Exit ===");
            logger.info(
                    "Return result - Total pages: {}, Current page: {}, Total records: {}",
                    responsePage.getTotalPages(),
                    responsePage.getNumber(),
                    responsePage.getTotalElements());

            return responsePage;
        } catch (Exception e) {
            logger.error("Paginated query user orders exception", e);
            throw e;
        }
    }

    /**
     * Delete order
     */
    public boolean deleteOrder(Long userId, String orderId) {
        logger.info("=== OrderService.deleteOrder Entry ===");
        logger.info("Request parameters - userId: {}, orderId: {}", userId, orderId);

        try {
            // Validate if user exists, throw exception if not
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                orderMapper.deleteByUserIdAndOrderId(userId, orderId);
                logger.info("=== OrderService.deleteOrder Exit ===");
                logger.info("Return result - Deletion successful");
                return true;
            } else {
                logger.info("=== OrderService.deleteOrder Exit ===");
                logger.info("Return result - Order does not exist");
                return false;
            }
        } catch (Exception e) {
            logger.error("Delete order exception", e);
            throw e;
        }
    }

    /**
     * Update order remark
     */
    public OrderResponse updateOrderRemark(Long userId, String orderId, String remark) {
        logger.info("=== OrderService.updateOrderRemark Entry ===");
        logger.info(
                "Request parameters - userId: {}, orderId: {}, remark: {}",
                userId,
                orderId,
                remark);

        try {
            // Validate if user exists, throw exception if not
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                order.setRemark(remark);
                order.onUpdate();
                orderMapper.updateById(order);

                logger.info("=== OrderService.updateOrderRemark Exit ===");
                logger.info("Return result - Update successful");
                return new OrderResponse(order);
            } else {
                logger.info("=== OrderService.updateOrderRemark Exit ===");
                logger.info("Return result - Order does not exist");
                return null;
            }
        } catch (Exception e) {
            logger.error("Update order remark exception", e);
            throw e;
        }
    }

    /**
     * Check product stock (compatible with original MCP interface)
     */
    public boolean checkStock(String productName, int quantity) {
        logger.info("=== OrderService.checkStock Entry ===");
        logger.info("Request parameters - productName: {}, quantity: {}", productName, quantity);

        try {
            // Query product stock from database
            boolean available = productMapper.checkStockAvailability(productName, quantity);

            logger.info("=== OrderService.checkStock Exit ===");
            logger.info("Return result - available: {}", available);

            return available;
        } catch (Exception e) {
            logger.error("Check stock exception", e);
            return false;
        }
    }

    /**
     * Get all available products
     */
    public List<Product> getAvailableProducts() {
        logger.info("=== OrderService.getAvailableProducts Entry ===");

        List<Product> products = productMapper.selectByStatusTrueOrderByName();

        logger.info("=== OrderService.getAvailableProducts Exit ===");
        logger.info("Return result - Total products: {}", products.size());

        return products;
    }

    /**
     * Get product information by product name
     */
    public Product getProductByName(String productName) {
        logger.info("=== OrderService.getProductByName Entry ===");
        logger.info("Request parameter - productName: {}", productName);

        Product product = productMapper.selectByNameAndStatus(productName, 1);

        logger.info("=== OrderService.getProductByName Exit ===");
        logger.info("Return result - product: {}", product != null ? product.getName() : "null");

        return product;
    }

    /**
     * Validate if product exists and is available
     */
    public boolean validateProduct(String productName) {
        logger.info("=== OrderService.validateProduct Entry ===");
        logger.info("Request parameter - productName: {}", productName);

        boolean exists = productMapper.existsByNameAndStatusTrue(productName);

        logger.info("=== OrderService.validateProduct Exit ===");
        logger.info("Return result - exists: {}", exists);

        return exists;
    }

    /**
     * Convert sweetness string to number
     */
    private Integer convertSweetnessToNumber(String sweetness) {
        if (sweetness == null) return 5; // Default: Regular Sugar
        switch (sweetness.toLowerCase()) {
            case "no sugar":
                return 1;
            case "light sugar":
                return 2;
            case "half sugar":
                return 3;
            case "less sugar":
                return 4;
            case "regular sugar":
                return 5;
            default:
                return 5;
        }
    }

    /**
     * Convert ice level string to number
     */
    private Integer convertIceLevelToNumber(String iceLevel) {
        if (iceLevel == null) return 5; // Default: Regular Ice
        switch (iceLevel.toLowerCase()) {
            case "hot":
                return 1;
            case "warm":
                return 2;
            case "no ice":
                return 3;
            case "less ice":
                return 4;
            case "regular ice":
                return 5;
            default:
                return 5;
        }
    }
}
