-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- =============================================
-- 云边奶茶铺系统数据库表结构
-- 用于 business-mcp-server 数据库初始化
-- =============================================

-- =============================================
-- 1. 用户表 (users)
-- =============================================
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT(20) NOT NULL COMMENT '用户ID（11位随机数）',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- =============================================
-- 2. 产品表 (products)
-- =============================================
CREATE TABLE IF NOT EXISTS `products` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '产品ID',
    `name` VARCHAR(100) NOT NULL COMMENT '产品名称',
    `description` TEXT COMMENT '产品描述',
    `price` DECIMAL(10,2) NOT NULL COMMENT '价格',
    `stock` INT(11) DEFAULT 0 COMMENT '库存数量',
    `shelf_time` INT(11) DEFAULT 30 COMMENT '保质期（分钟）',
    `preparation_time` INT(11) DEFAULT 5 COMMENT '制作时间（分钟）',
    `is_seasonal` TINYINT(1) DEFAULT 0 COMMENT '是否季节性产品：0-否，1-是',
    `season_start` DATE DEFAULT NULL COMMENT '季节开始时间',
    `season_end` DATE DEFAULT NULL COMMENT '季节结束时间',
    `is_regional` TINYINT(1) DEFAULT 0 COMMENT '是否地区限定：0-否，1-是',
    `available_regions` JSON DEFAULT NULL COMMENT '可用地区列表',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态：0-下架，1-上架',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status` (`status`),
    KEY `idx_is_seasonal` (`is_seasonal`),
    KEY `idx_is_regional` (`is_regional`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品表';

-- =============================================
-- 3. 订单表 (orders)
-- =============================================
CREATE TABLE IF NOT EXISTS `orders` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_id` VARCHAR(50) NOT NULL COMMENT '订单编号',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `product_id` BIGINT(20) NOT NULL COMMENT '产品ID',
    `product_name` VARCHAR(100) NOT NULL COMMENT '产品名称',
    `sweetness` TINYINT(1) NOT NULL COMMENT '甜度：1-无糖，2-微糖，3-半糖，4-少糖，5-标准糖',
    `ice_level` TINYINT(1) NOT NULL COMMENT '冰量：1-热，2-温，3-去冰，4-少冰，5-正常冰',
    `quantity` INT(11) NOT NULL DEFAULT 1 COMMENT '数量',
    `unit_price` DECIMAL(10,2) NOT NULL COMMENT '单价',
    `total_price` DECIMAL(10,2) NOT NULL COMMENT '总价',
    `remark` TEXT DEFAULT NULL COMMENT '订单备注',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_created_at` (`created_at`),
    CONSTRAINT `fk_orders_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_orders_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- =============================================
-- 4. 反馈表 (feedback)
-- =============================================
CREATE TABLE IF NOT EXISTS `feedback` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    `order_id` VARCHAR(50) DEFAULT NULL COMMENT '关联订单ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `feedback_type` TINYINT(2) NOT NULL COMMENT '反馈类型：1-产品反馈，2-服务反馈，3-投诉，4-建议',
    `rating` TINYINT(1) DEFAULT NULL COMMENT '评分（1-5星）',
    `content` TEXT NOT NULL COMMENT '反馈内容',
    `solution` TEXT DEFAULT NULL COMMENT '处理方案',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '反馈时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_feedback_type` (`feedback_type`),
    KEY `idx_rating` (`rating`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈表';

