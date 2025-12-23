/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.supervisor.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.bobatea.supervisor.entity.Feedback;
import io.agentscope.examples.bobatea.supervisor.entity.Order;
import io.agentscope.examples.bobatea.supervisor.entity.Product;
import io.agentscope.examples.bobatea.supervisor.mapper.FeedbackMapper;
import io.agentscope.examples.bobatea.supervisor.mapper.OrderMapper;
import io.agentscope.examples.bobatea.supervisor.mapper.ProductMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * ScheduleAgentTools
 * @author yaohui
 **/
@Component
public class ScheduleAgentTools {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleAgentTools.class);

    @Value("${agent.dingtalk.access-token}")
    private String accessToken;

    @Autowired private FeedbackMapper feedbackMapper;

    @Autowired private OrderMapper orderMapper;

    @Autowired private ProductMapper productMapper;

    private static final String DEFAULT_WEBHOOK_URL_TEMPLATE =
            "https://oapi.dingtalk.com/robot/send?access_token=%s";

    @Tool(description = "获取经营报告数据信息")
    public Map<String, Object> getDailyReportInfo() {
        // === 模拟测试数据，直接按当前测试数据最大时间来获取
        String maxMonth = orderMapper.selectMaxCreatedMonth();
        System.out.println("DailyReportInfo month: " + maxMonth);
        Date startTime;
        Date endTime;
        if (maxMonth != null && !maxMonth.isEmpty()) {
            // Parse the maxMonth string (format: "yyyy-MM") to create the first day of that month
            try {
                YearMonth yearMonth = YearMonth.parse(maxMonth);
                LocalDate firstDayOfMonth = yearMonth.atDay(1);
                // Convert to Date objects
                startTime =
                        Date.from(firstDayOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (Exception e) {
                // Fallback to default behavior if parsing fails
                startTime =
                        new Date(
                                System.currentTimeMillis()
                                        - 365L * 24 * 60 * 60 * 1000); // One year ago
            }
        } else {
            // Fallback to default behavior if maxMonth is null or empty
            startTime =
                    new Date(
                            System.currentTimeMillis()
                                    - 365L * 24 * 60 * 60 * 1000); // One year ago
        }
        endTime = new Date();
        // === 模拟测试数据，直接按当前测试数据最大时间来获取

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("store_name", "云原生" + 1 + "号门店");

        String content = "";

        // == 订单销售数据获取 start
        List<Order> todayOrders = orderMapper.findOrdersByTimeRange(startTime, endTime);
        int todayOrderCount = todayOrders.size();
        BigDecimal totalRevenue =
                todayOrders.stream()
                        .map(Order::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        Date yesterdayStartTime =
                new Date(startTime.getTime() - (365L * 24 * 60 * 60 * 1000)); // One year ago
        Date yesterdayEndTime = startTime;
        List<Order> yesterdayOrders =
                orderMapper.findOrdersByTimeRange(yesterdayStartTime, yesterdayEndTime);
        int yesterdayOrderCount = yesterdayOrders.size();
        BigDecimal yesterdayTotalRevenue =
                yesterdayOrders.stream()
                        .map(Order::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        templateData.put("total_sales", todayOrderCount);
        templateData.put("yesterday_total_sales", yesterdayOrderCount);
        templateData.put("total_revenue", String.format("%.2f", totalRevenue));
        templateData.put(
                "avg_price",
                totalRevenue
                        .divide(new BigDecimal(todayOrderCount), 2, RoundingMode.HALF_UP)
                        .doubleValue());

        templateData.put(
                "sales_growth",
                String.format(
                                (totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue()
                                                >= 0)
                                        ? "📈"
                                        : "📉" + " %.2f",
                                (totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue())
                                        / yesterdayTotalRevenue.doubleValue()
                                        * 100)
                        + "%");
        templateData.put(
                "order_change",
                String.format(
                                (todayOrderCount - yesterdayOrderCount >= 0) ? "📈" : "📉" + "%.2f",
                                (((double) todayOrderCount - (double) yesterdayOrderCount)
                                        / (double) yesterdayOrderCount
                                        * 100D))
                        + "%");
        // == 订单销售数据获取 end

        // ==  获取评价反馈数据 start
        List<Feedback> validFeedbacks = feedbackMapper.selectByTimeRange(startTime, endTime);
        List<String> feedbackStr =
                validFeedbacks.stream().map(Feedback::toFormattedString).toList();
        templateData.put(
                "feedbacks", validFeedbacks.stream().map(Feedback::toFormattedString).toList());
        content += "用户评价反馈信息：\n" + feedbackStr.stream().collect(Collectors.joining("\n"));

        // Calculate review statistics
        int totalValidFeedbacks = validFeedbacks.size();
        long positiveCount = validFeedbacks.stream().filter(f -> f.getRating() == 5).count();
        long neutralCount =
                validFeedbacks.stream()
                        .filter(f -> f.getRating() >= 3 && f.getRating() <= 4)
                        .count();
        long negativeCount = validFeedbacks.stream().filter(f -> f.getRating() < 3).count();

        // Calculate percentages
        double positiveRate =
                totalValidFeedbacks > 0 ? (positiveCount * 100.0 / totalValidFeedbacks) : 0;
        double neutralRate =
                totalValidFeedbacks > 0 ? (neutralCount * 100.0 / totalValidFeedbacks) : 0;
        double negativeRate =
                totalValidFeedbacks > 0 ? (negativeCount * 100.0 / totalValidFeedbacks) : 0;

        // Calculate rating distribution (1-5 stars)
        long[] ratingDistribution = new long[5];
        for (int i = 0; i < 5; i++) {
            final int rating = i + 1;
            ratingDistribution[i] =
                    validFeedbacks.stream()
                            .filter(f -> f.getRating() != null && f.getRating() == rating)
                            .count();
        }

        // Calculate percentage distribution
        double[] ratingPercentage = new double[5];
        for (int i = 0; i < 5; i++) {
            ratingPercentage[i] =
                    totalValidFeedbacks > 0
                            ? (ratingDistribution[i] * 100.0 / totalValidFeedbacks)
                            : 0;
        }

        // Add review statistics
        templateData.put("positive_rate", String.format("%.0f", positiveRate) + "%");
        templateData.put("neutral_rate", String.format("%.0f", neutralRate) + "%");
        templateData.put("negative_rate", String.format("%.0f", negativeRate) + "%");

        // Format date and time in yyyy-MM-dd HH:mm:ss format
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        templateData.put("report_date", LocalDate.now().format(dateFormatter));
        templateData.put(
                "report_time",
                LocalDate.now().format(dateFormatter)
                        + " "
                        + LocalTime.now().format(timeFormatter));

        // Add rating distribution
        for (int i = 0; i < 5; i++) {
            templateData.put(
                    "star" + (i + 1) + "_rate", String.format("%.0f", ratingPercentage[i]));
        }
        // ==  获取评价反馈数据 end

        // 找出销售额最大的前3个产品
        Map<Long, BigDecimal> productSalesRevenueMap =
                todayOrders.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Order::getProductId,
                                        Collectors.reducing(
                                                BigDecimal.ZERO,
                                                Order::getTotalPrice,
                                                BigDecimal::add)));
        List<Map.Entry<Long, BigDecimal>> top3ByRevenue =
                productSalesRevenueMap.entrySet().stream()
                        .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                        .limit(3)
                        .collect(Collectors.toList());
        // Add top 3 products by sales count
        content += "\n产品销量说明：\n";
        for (int i = 0; i < 3; i++) {
            if (i < top3ByRevenue.size()) {
                Map.Entry<Long, BigDecimal> entry = top3ByRevenue.get(i);
                // Get product name from productMapper or use a default name
                String productName = "Product " + entry.getKey();
                Product product = null;
                try {
                    // Try to get the actual product name
                    product = productMapper.selectById(entry.getKey());
                    if (product != null && product.getName() != null) {
                        productName = product.getName();
                    }
                } catch (Exception e) {
                    // Use default name if product not found
                }
                templateData.put("r_product" + (i + 1), productName);
                templateData.put(
                        "r_product" + (i + 1) + "_quantity",
                        String.format("%.2f", entry.getValue()));
                // Calculate percentage of total sales
                double percentage =
                        (entry.getValue().doubleValue() * 100.0) / totalRevenue.doubleValue();
                templateData.put(
                        "r_product" + (i + 1) + "_percentage", String.format("%.1f", percentage));

                content +=
                        productName
                                + " 销售额排名第"
                                + (i + 1)
                                + "，销售额为 "
                                + String.format("%.2f", entry.getValue())
                                + "，占比为 "
                                + String.format("%.1f", percentage)
                                + "%, 产品单价："
                                + (product != null ? product.getPrice() : "")
                                + ", 产品描述："
                                + (product != null ? product.getDescription() : "")
                                + "\n";
            } else {
                templateData.put("r_product" + (i + 1), "N/A");
                templateData.put("r_product" + (i + 1) + "_quantity", 0);
                templateData.put("r_product" + (i + 1) + "_percentage", "0.0");
            }
        }

        // 找出销量最大的前3个产品
        Map<Long, Integer> productSalesCountMap =
                todayOrders.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Order::getProductId,
                                        Collectors.summingInt(Order::getQuantity)));
        List<Map.Entry<Long, Integer>> top3BySalesCount =
                productSalesCountMap.entrySet().stream()
                        .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                        .limit(3)
                        .collect(Collectors.toList());
        for (int i = 0; i < 3; i++) {
            if (i < top3BySalesCount.size()) {
                Map.Entry<Long, Integer> entry = top3BySalesCount.get(i);
                // Get product name from productMapper or use a default name
                String productName = "Product " + entry.getKey();
                Product product = null;
                try {
                    // Try to get the actual product name
                    product = productMapper.selectById(entry.getKey());
                    if (product != null && product.getName() != null) {
                        productName = product.getName();
                    }
                } catch (Exception e) {
                    // Use default name if product not found
                }
                templateData.put("product" + (i + 1), productName);
                templateData.put("product" + (i + 1) + "_quantity", entry.getValue());
                // Calculate percentage of total sales
                double percentage = (entry.getValue() * 100.0) / todayOrderCount;
                templateData.put(
                        "product" + (i + 1) + "_percentage", String.format("%.1f", percentage));
                content +=
                        productName
                                + " 销售量排名第"
                                + (i + 1)
                                + "，销量为 "
                                + entry.getValue()
                                + "，占比为 "
                                + String.format("%.1f", percentage)
                                + "%, 产品描述："
                                + (product != null ? product.getDescription() : "")
                                + "\n";
            } else {
                templateData.put("product" + (i + 1), "N/A");
                templateData.put("product" + (i + 1) + "_quantity", 0);
                templateData.put("product" + (i + 1) + "_percentage", "0.0");
            }
        }
        templateData.put("content", content);
        return templateData;
    }

    @Tool(description = "用于存储报告文档并通过钉钉机器人发送报告")
    public String sendReport(@ToolParam(name = "text", description = "经营报告内容") String text) {
        logger.info("\n>>> 经营报告:\n{}", text);

        // 保存报告为 MD 文件
        try {
            saveReportToFile(text);
        } catch (IOException e) {
            logger.error("保存报告文件失败", e);
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> requestBody = createRequestBody("门店经营报告", text);
        String requestBodyJson = null;
        try {
            requestBodyJson = new ObjectMapper().writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        HttpEntity<String> request = new HttpEntity<>(requestBodyJson, headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        String.format(DEFAULT_WEBHOOK_URL_TEMPLATE, accessToken),
                        request,
                        String.class);
        return response.getBody();
    }

    /**
     * 将报告内容保存为 MD 文件
     * @param text 报告内容
     * @throws IOException IO异常
     */
    private void saveReportToFile(String text) throws IOException {
        // 获取系统 user.dir 属性
        String userDir = System.getProperty("user.dir");

        // 创建 reports 目录
        Path reportsDir = Paths.get(userDir, "reports");
        if (!Files.exists(reportsDir)) {
            Files.createDirectories(reportsDir);
            logger.info("创建报告目录: {}", reportsDir.toAbsolutePath());
        }

        // 生成文件名（使用时间戳）
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        String fileName = String.format("经营报告_%s.md", timestamp);

        // 保存文件
        Path filePath = reportsDir.resolve(fileName);
        Files.writeString(
                filePath, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("报告已保存至: {}", filePath.toAbsolutePath());
    }

    private Map<String, Object> createRequestBody(String title, String messageContent) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("msgtype", "markdown");
        Map<String, String> markdown = new HashMap<>();
        markdown.put("title", title);
        markdown.put("text", messageContent);
        requestBody.put("markdown", markdown);
        return requestBody;
    }
}
