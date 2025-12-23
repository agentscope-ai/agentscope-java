/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * 订单服务类
 * 提供订单相关的业务逻辑
 */
@Service
@Transactional
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired private OrderMapper orderMapper;

    @Autowired private ProductMapper productMapper;

    @Autowired private UserMapper userMapper;

    /**
     * 验证用户是否存在，如果不存在则抛出异常
     */
    public User validateUser(Long userId) {
        logger.info("=== OrderService.validateUser 入口 ===");
        logger.info("请求参数 - userId: {}", userId);

        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空，请提供有效的用户ID");
        }

        try {
            // 检查用户是否存在
            User existingUser = userMapper.selectById(userId);
            if (existingUser == null) {
                throw new IllegalArgumentException("用户不存在，用户ID: " + userId + "，请先注册用户");
            }

            logger.info("=== OrderService.validateUser 出口 ===");
            logger.info("返回结果 - 用户验证成功: {}", existingUser.getUsername());

            return existingUser;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.error("验证用户异常", e);
            throw new RuntimeException("用户验证失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建订单（兼容原有MCP接口）
     */
    public Order createOrder(String productName, String sweetness, String iceLevel, int quantity) {
        logger.info("=== OrderService.createOrder 入口 ===");
        logger.info(
                "请求参数 - productName: {}, sweetness: {}, iceLevel: {}, quantity: {}",
                productName,
                sweetness,
                iceLevel,
                quantity);

        try {
            // 转换甜度和冰量为数字
            Integer sweetnessLevel = convertSweetnessToNumber(sweetness);
            Integer iceLevelNumber = convertIceLevelToNumber(iceLevel);

            // 从数据库查询产品信息
            Product product = productMapper.selectByNameAndStatus(productName, 1);
            if (product == null) {
                throw new IllegalArgumentException("产品不存在或已下架: " + productName);
            }

            // 检查库存
            if (product.getStock() < quantity) {
                String errorMsg =
                        String.format(
                                "库存不足，产品: %s, 当前库存: %d, 需要数量: %d",
                                productName, product.getStock(), quantity);
                logger.error("创建订单失败: {}", errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(quantity));
            String orderId = "ORDER_" + System.currentTimeMillis();

            // 创建订单实体
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

            // 保存到数据库
            orderMapper.insert(order);

            // 更新产品库存
            product.setStock(product.getStock() - quantity);
            product.onUpdate();
            productMapper.updateById(product);

            logger.info("=== OrderService.createOrder 出口 ===");
            logger.info(
                    "返回结果 - orderId: {}, productName: {}, sweetness: {}, iceLevel: {}, quantity:"
                            + " {}, price: {}",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetness(),
                    order.getIceLevel(),
                    order.getQuantity(),
                    order.getTotalPrice());

            return order;
        } catch (Exception e) {
            logger.error("创建订单异常", e);
            throw e;
        }
    }

    /**
     * 创建订单（新接口）
     */
    public OrderResponse createOrder(OrderCreateRequest request) {
        logger.info("=== OrderService.createOrder 入口 ===");
        logger.info("请求参数 - {}", request);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            User user = validateUser(request.getUserId());

            // 从数据库查询产品信息
            Product product = productMapper.selectByNameAndStatus(request.getProductName(), 1);
            if (product == null) {
                throw new IllegalArgumentException("产品不存在或已下架: " + request.getProductName());
            }

            // 检查库存
            if (product.getStock() < request.getQuantity()) {
                String errorMsg =
                        String.format(
                                "库存不足，产品: %s, 当前库存: %d, 需要数量: %d",
                                request.getProductName(),
                                product.getStock(),
                                request.getQuantity());
                logger.error("创建订单失败: {}", errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            BigDecimal unitPrice = product.getPrice();
            BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(request.getQuantity()));
            String orderId = "ORDER_" + System.currentTimeMillis();

            // 创建订单实体
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

            // 保存到数据库
            orderMapper.insert(order);

            // 更新产品库存
            product.setStock(product.getStock() - request.getQuantity());
            product.onUpdate();
            productMapper.updateById(product);

            logger.info("=== OrderService.createOrder 出口 ===");
            logger.info("返回结果 - orderId: {}", order.getOrderId());

            return new OrderResponse(order);
        } catch (Exception e) {
            logger.error("创建订单异常", e);
            throw e;
        }
    }

    /**
     * 查询订单（兼容原有MCP接口）
     */
    public Order getOrder(String orderId) {
        logger.info("=== OrderService.getOrder 入口 ===");
        logger.info("请求参数 - orderId: {}", orderId);

        Order order = orderMapper.selectByOrderId(orderId);

        logger.info("=== OrderService.getOrder 出口 ===");
        if (order != null) {
            logger.info(
                    "返回结果 - orderId: {}, productName: {}, sweetness: {}, iceLevel: {}, quantity:"
                            + " {}, price: {}, createTime: {}",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetness(),
                    order.getIceLevel(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt());
        } else {
            logger.info("返回结果 - 订单不存在");
        }

        return order;
    }

    /**
     * 根据用户ID和订单ID查询订单
     */
    public OrderResponse getOrderByUserIdAndOrderId(Long userId, String orderId) {
        logger.info("=== OrderService.getOrderByUserIdAndOrderId 入口 ===");
        logger.info("请求参数 - userId: {}, orderId: {}", userId, orderId);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                OrderResponse response = new OrderResponse(order);
                logger.info("=== OrderService.getOrderByUserIdAndOrderId 出口 ===");
                logger.info("返回结果 - orderId: {}", response.getOrderId());
                return response;
            } else {
                logger.info("=== OrderService.getOrderByUserIdAndOrderId 出口 ===");
                logger.info("返回结果 - 订单不存在");
                return null;
            }
        } catch (Exception e) {
            logger.error("查询订单异常", e);
            throw e;
        }
    }

    /**
     * 获取所有订单（兼容原有MCP接口）
     */
    public List<Order> getAllOrders() {
        logger.info("=== OrderService.getAllOrders 入口 ===");

        List<Order> allOrders = orderMapper.selectAll();

        logger.info("=== OrderService.getAllOrders 出口 ===");
        logger.info("返回结果 - 订单总数: {}", allOrders.size());

        return allOrders;
    }

    /**
     * 根据用户ID查询订单列表
     */
    public List<OrderResponse> getOrdersByUserId(Long userId) {
        logger.info("=== OrderService.getOrdersByUserId 入口 ===");
        logger.info("请求参数 - userId: {}", userId);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            validateUser(userId);

            List<Order> orders = orderMapper.selectByUserId(userId);
            List<OrderResponse> responses =
                    orders.stream().map(OrderResponse::new).collect(Collectors.toList());

            logger.info("=== OrderService.getOrdersByUserId 出口 ===");
            logger.info("返回结果 - 订单总数: {}", responses.size());

            return responses;
        } catch (Exception e) {
            logger.error("查询用户订单异常", e);
            throw e;
        }
    }

    /**
     * 多维度查询用户订单
     */
    public List<OrderResponse> queryOrders(OrderQueryRequest request) {
        logger.info("=== OrderService.queryOrders 入口 ===");
        logger.info("请求参数 - {}", request);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
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

            logger.info("=== OrderService.queryOrders 出口 ===");
            logger.info("返回结果 - 订单总数: {}", responses.size());

            return responses;
        } catch (Exception e) {
            logger.error("查询订单异常", e);
            throw e;
        }
    }

    /**
     * 分页查询用户订单
     */
    public Page<OrderResponse> getOrdersByUserIdWithPagination(Long userId, Pageable pageable) {
        logger.info("=== OrderService.getOrdersByUserIdWithPagination 入口 ===");
        logger.info(
                "请求参数 - userId: {}, page: {}, size: {}",
                userId,
                pageable.getPageNumber(),
                pageable.getPageSize());

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            validateUser(userId);

            int offset = (int) pageable.getOffset();
            int size = pageable.getPageSize();
            List<Order> orders = orderMapper.selectByUserIdWithPagination(userId, offset, size);
            long total = orderMapper.countByUserId(userId);
            Page<Order> orderPage = new PageImpl<>(orders, pageable, total);
            Page<OrderResponse> responsePage = orderPage.map(OrderResponse::new);

            logger.info("=== OrderService.getOrdersByUserIdWithPagination 出口 ===");
            logger.info(
                    "返回结果 - 总页数: {}, 当前页: {}, 总记录数: {}",
                    responsePage.getTotalPages(),
                    responsePage.getNumber(),
                    responsePage.getTotalElements());

            return responsePage;
        } catch (Exception e) {
            logger.error("分页查询用户订单异常", e);
            throw e;
        }
    }

    /**
     * 删除订单
     */
    public boolean deleteOrder(Long userId, String orderId) {
        logger.info("=== OrderService.deleteOrder 入口 ===");
        logger.info("请求参数 - userId: {}, orderId: {}", userId, orderId);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                orderMapper.deleteByUserIdAndOrderId(userId, orderId);
                logger.info("=== OrderService.deleteOrder 出口 ===");
                logger.info("返回结果 - 删除成功");
                return true;
            } else {
                logger.info("=== OrderService.deleteOrder 出口 ===");
                logger.info("返回结果 - 订单不存在");
                return false;
            }
        } catch (Exception e) {
            logger.error("删除订单异常", e);
            throw e;
        }
    }

    /**
     * 更新订单备注
     */
    public OrderResponse updateOrderRemark(Long userId, String orderId, String remark) {
        logger.info("=== OrderService.updateOrderRemark 入口 ===");
        logger.info("请求参数 - userId: {}, orderId: {}, remark: {}", userId, orderId, remark);

        try {
            // 验证用户是否存在，如果不存在则抛出异常
            validateUser(userId);

            Order order = orderMapper.selectByUserIdAndOrderId(userId, orderId);
            if (order != null) {
                order.setRemark(remark);
                order.onUpdate();
                orderMapper.updateById(order);

                logger.info("=== OrderService.updateOrderRemark 出口 ===");
                logger.info("返回结果 - 更新成功");
                return new OrderResponse(order);
            } else {
                logger.info("=== OrderService.updateOrderRemark 出口 ===");
                logger.info("返回结果 - 订单不存在");
                return null;
            }
        } catch (Exception e) {
            logger.error("更新订单备注异常", e);
            throw e;
        }
    }

    /**
     * 检查产品库存（兼容原有MCP接口）
     */
    public boolean checkStock(String productName, int quantity) {
        logger.info("=== OrderService.checkStock 入口 ===");
        logger.info("请求参数 - productName: {}, quantity: {}", productName, quantity);

        try {
            // 从数据库查询产品库存
            boolean available = productMapper.checkStockAvailability(productName, quantity);

            logger.info("=== OrderService.checkStock 出口 ===");
            logger.info("返回结果 - available: {}", available);

            return available;
        } catch (Exception e) {
            logger.error("检查库存异常", e);
            return false;
        }
    }

    /**
     * 获取所有可用产品
     */
    public List<Product> getAvailableProducts() {
        logger.info("=== OrderService.getAvailableProducts 入口 ===");

        List<Product> products = productMapper.selectByStatusTrueOrderByName();

        logger.info("=== OrderService.getAvailableProducts 出口 ===");
        logger.info("返回结果 - 产品总数: {}", products.size());

        return products;
    }

    /**
     * 根据产品名称获取产品信息
     */
    public Product getProductByName(String productName) {
        logger.info("=== OrderService.getProductByName 入口 ===");
        logger.info("请求参数 - productName: {}", productName);

        Product product = productMapper.selectByNameAndStatus(productName, 1);

        logger.info("=== OrderService.getProductByName 出口 ===");
        logger.info("返回结果 - product: {}", product != null ? product.getName() : "null");

        return product;
    }

    /**
     * 验证产品是否存在且可用
     */
    public boolean validateProduct(String productName) {
        logger.info("=== OrderService.validateProduct 入口 ===");
        logger.info("请求参数 - productName: {}", productName);

        boolean exists = productMapper.existsByNameAndStatusTrue(productName);

        logger.info("=== OrderService.validateProduct 出口 ===");
        logger.info("返回结果 - exists: {}", exists);

        return exists;
    }

    /**
     * 甜度字符串转数字
     */
    private Integer convertSweetnessToNumber(String sweetness) {
        if (sweetness == null) return 5; // 默认标准糖
        switch (sweetness.toLowerCase()) {
            case "无糖":
                return 1;
            case "微糖":
                return 2;
            case "半糖":
                return 3;
            case "少糖":
                return 4;
            case "标准糖":
                return 5;
            default:
                return 5;
        }
    }

    /**
     * 冰量字符串转数字
     */
    private Integer convertIceLevelToNumber(String iceLevel) {
        if (iceLevel == null) return 5; // 默认正常冰
        switch (iceLevel.toLowerCase()) {
            case "热":
                return 1;
            case "温":
                return 2;
            case "去冰":
                return 3;
            case "少冰":
                return 4;
            case "正常冰":
                return 5;
            default:
                return 5;
        }
    }
}
