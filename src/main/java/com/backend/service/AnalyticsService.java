package com.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.DashboardOverviewResponse;
import com.backend.dto.DashboardOverviewResponse.ChannelProductCount;
import com.backend.dto.DashboardOverviewResponse.RecentOrder;
import com.backend.dto.RevenueAnalyticsResponse;
import com.backend.dto.RevenueAnalyticsResponse.RevenueChartPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String DASHBOARD_REVENUE_STATUSES = "'CONFIRMED', 'READY_TO_SHIP'";
    private static final String ANALYTICS_REVENUE_STATUSES =
            "'CONFIRMED', 'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED'";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyticsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DashboardOverviewResponse overview(String tenantId) {
        ZonedDateTime startOfToday = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE);
        Instant start = startOfToday.toInstant();
        Instant end = startOfToday.plusDays(1).toInstant();

        BigDecimal todayRevenue = amount("""
                SELECT COALESCE(SUM(total_amount), 0)
                FROM orders
                WHERE tenant_id = :tenantId
                  AND deleted_at IS NULL
                  AND external_created_at >= :startAt
                  AND external_created_at < :endAt
                  AND canonical_status IN (%s)
                """.formatted(DASHBOARD_REVENUE_STATUSES), tenantId, start, end);

        long newOrders = count("""
                SELECT COUNT(*)
                FROM orders
                WHERE tenant_id = :tenantId
                  AND deleted_at IS NULL
                  AND external_created_at >= :startAt
                  AND external_created_at < :endAt
                """, tenantId, start, end);

        long newCustomers = count("""
                SELECT COUNT(*)
                FROM (
                  SELECT c.marketplace_customer_id
                  FROM conversations c
                  JOIN messages m
                    ON m.conversation_id = c.id AND m.tenant_id = c.tenant_id
                  WHERE c.tenant_id = :tenantId
                    AND m.direction = 'INBOUND'
                    AND m.sender_type = 'CUSTOMER'
                  GROUP BY c.marketplace_customer_id
                  HAVING MIN(COALESCE(m.external_created_at, m.created_at)) >= :startAt
                     AND MIN(COALESCE(m.external_created_at, m.created_at)) < :endAt
                ) first_contacts
                """, tenantId, start, end);

        Map<String, ChannelProductCount> channels = new LinkedHashMap<>();
        channels.put("TIKTOK_SHOP", new ChannelProductCount("TIKTOK_SHOP", "TikTok Shop", 0));
        channels.put("LAZADA", new ChannelProductCount("LAZADA", "Lazada", 0));
        jdbcClient.sql("""
                SELECT m.marketplace_code, m.marketplace_name, COUNT(DISTINCT mp.id) AS product_count
                FROM marketplaces m
                LEFT JOIN marketplace_accounts ma
                  ON ma.marketplace_id = m.id
                 AND ma.tenant_id = :tenantId
                 AND ma.deleted_at IS NULL
                LEFT JOIN marketplace_products mp
                  ON mp.marketplace_account_id = ma.id
                 AND mp.tenant_id = :tenantId
                 AND mp.deleted_at IS NULL
                 AND mp.canonical_status <> 'DELETED'
                WHERE m.marketplace_code IN ('TIKTOK_SHOP', 'LAZADA')
                GROUP BY m.marketplace_code, m.marketplace_name
                ORDER BY m.marketplace_code DESC
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ChannelProductCount(
                        rs.getString("marketplace_code"),
                        rs.getString("marketplace_name"),
                        rs.getLong("product_count")))
                .list()
                .forEach(channel -> channels.put(channel.marketplace(), channel));

        List<RecentOrder> recentOrders = jdbcClient.sql("""
                SELECT o.id, o.external_order_id,
                       COALESCE(mc.display_name, 'Khách hàng') AS customer_name,
                       m.marketplace_code, m.marketplace_name,
                       o.total_amount, o.canonical_status, o.external_created_at
                FROM orders o
                JOIN marketplace_accounts ma ON ma.id = o.marketplace_account_id
                JOIN marketplaces m ON m.id = ma.marketplace_id
                LEFT JOIN marketplace_customers mc ON mc.id = o.marketplace_customer_id
                WHERE o.tenant_id = :tenantId AND o.deleted_at IS NULL
                ORDER BY o.external_created_at DESC
                LIMIT 5
                """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new RecentOrder(
                        rs.getString("id"),
                        rs.getString("external_order_id"),
                        rs.getString("customer_name"),
                        rs.getString("marketplace_code"),
                        rs.getString("marketplace_name"),
                        money(rs.getBigDecimal("total_amount")),
                        rs.getString("canonical_status"),
                        rs.getTimestamp("external_created_at").toInstant()))
                .list();

        return new DashboardOverviewResponse(
                money(todayRevenue), newOrders, newCustomers,
                List.copyOf(channels.values()), recentOrders);
    }

    public RevenueAnalyticsResponse revenue(String tenantId, String period) {
        PeriodRange range = periodRange(period, Instant.now());
        return revenueForRange(
                tenantId,
                range.start(),
                range.end(),
                range.previousStart(),
                range.previousEnd(),
                chartGranularity(period));
    }

    public RevenueAnalyticsResponse revenueForRange(
            String tenantId,
            Instant start,
            Instant end,
            Instant previousStart,
            Instant previousEnd) {
        return revenueForRange(
                tenantId, start, end, previousStart, previousEnd, ChartGranularity.DAY);
    }

    private RevenueAnalyticsResponse revenueForRange(
            String tenantId,
            Instant start,
            Instant end,
            Instant previousStart,
            Instant previousEnd,
            ChartGranularity granularity) {
        BigDecimal revenue = revenueAmount(tenantId, start, end);
        BigDecimal previousRevenue = revenueAmount(tenantId, previousStart, previousEnd);
        BigDecimal shippingCost = amount("""
                SELECT COALESCE(SUM(shipping_amount), 0)
                FROM orders
                WHERE tenant_id = :tenantId
                  AND deleted_at IS NULL
                  AND external_created_at >= :startAt
                  AND external_created_at < :endAt
                  AND canonical_status IN (%s)
                """.formatted(ANALYTICS_REVENUE_STATUSES), tenantId, start, end);
        BigDecimal costOfGoods = costOfGoods(tenantId, start, end);
        BigDecimal totalCost = money(costOfGoods.add(shippingCost));
        BigDecimal netProfit = money(revenue.subtract(totalCost));
        BigDecimal margin = percentage(netProfit, revenue);
        BigDecimal growth = growth(revenue, previousRevenue);
        return new RevenueAnalyticsResponse(
                money(revenue), netProfit, totalCost, margin, growth, start, end,
                granularity.name(), revenueSeries(tenantId, start, end, granularity));
    }

    private List<RevenueChartPoint> revenueSeries(
            String tenantId,
            Instant start,
            Instant end,
            ChartGranularity granularity) {
        Map<String, BigDecimal> revenueByBucket = new LinkedHashMap<>();
        jdbcClient.sql("""
                SELECT total_amount, external_created_at
                FROM orders
                WHERE tenant_id = :tenantId
                  AND deleted_at IS NULL
                  AND external_created_at >= :startAt
                  AND external_created_at < :endAt
                  AND canonical_status IN (%s)
                ORDER BY external_created_at
                """.formatted(ANALYTICS_REVENUE_STATUSES))
                .param("tenantId", tenantId)
                .param("startAt", Timestamp.from(start))
                .param("endAt", Timestamp.from(end))
                .query((rs, rowNum) -> new RevenueOrder(
                        rs.getTimestamp("external_created_at").toInstant(),
                        rs.getBigDecimal("total_amount")))
                .list()
                .forEach(order -> revenueByBucket.merge(
                        bucketKey(order.createdAt(), granularity),
                        order.amount(),
                        BigDecimal::add));

        List<RevenueChartPoint> points = new ArrayList<>();
        ZonedDateTime cursor = bucketStart(start, granularity);
        while (cursor.toInstant().isBefore(end)) {
            String key = bucketKey(cursor.toInstant(), granularity);
            points.add(new RevenueChartPoint(
                    cursor.toInstant(),
                    bucketLabel(cursor, granularity),
                    money(revenueByBucket.getOrDefault(key, BigDecimal.ZERO))));
            cursor = nextBucket(cursor, granularity);
        }
        return List.copyOf(points);
    }

    private static ChartGranularity chartGranularity(String rawPeriod) {
        String period = rawPeriod == null ? "this-month" : rawPeriod.trim().toLowerCase(Locale.ROOT);
        return switch (period) {
            case "this-month", "last-month" -> ChartGranularity.DAY;
            case "q3", "this-quarter" -> ChartGranularity.MONTH;
            case "full-year", "this-year" -> ChartGranularity.QUARTER;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANALYTICS_PERIOD",
                    "Khoảng thời gian phân tích không hợp lệ.");
        };
    }

    private static ZonedDateTime bucketStart(Instant start, ChartGranularity granularity) {
        ZonedDateTime value = start.atZone(BUSINESS_ZONE);
        return switch (granularity) {
            case DAY -> value.toLocalDate().atStartOfDay(BUSINESS_ZONE);
            case MONTH -> value.withDayOfMonth(1).toLocalDate().atStartOfDay(BUSINESS_ZONE);
            case QUARTER -> {
                int firstMonth = ((value.getMonthValue() - 1) / 3) * 3 + 1;
                yield YearMonth.of(value.getYear(), firstMonth)
                        .atDay(1).atStartOfDay(BUSINESS_ZONE);
            }
        };
    }

    private static ZonedDateTime nextBucket(ZonedDateTime cursor, ChartGranularity granularity) {
        return switch (granularity) {
            case DAY -> cursor.plusDays(1);
            case MONTH -> cursor.plusMonths(1);
            case QUARTER -> cursor.plusMonths(3);
        };
    }

    private static String bucketKey(Instant value, ChartGranularity granularity) {
        ZonedDateTime dateTime = value.atZone(BUSINESS_ZONE);
        return switch (granularity) {
            case DAY -> dateTime.toLocalDate().toString();
            case MONTH -> YearMonth.from(dateTime).toString();
            case QUARTER -> dateTime.getYear() + "-Q" + ((dateTime.getMonthValue() - 1) / 3 + 1);
        };
    }

    private static String bucketLabel(ZonedDateTime value, ChartGranularity granularity) {
        return switch (granularity) {
            case DAY -> "%02d/%02d".formatted(value.getDayOfMonth(), value.getMonthValue());
            case MONTH -> "Tháng " + value.getMonthValue();
            case QUARTER -> "Quý " + ((value.getMonthValue() - 1) / 3 + 1);
        };
    }

    private BigDecimal revenueAmount(String tenantId, Instant start, Instant end) {
        return amount("""
                SELECT COALESCE(SUM(total_amount), 0)
                FROM orders
                WHERE tenant_id = :tenantId
                  AND deleted_at IS NULL
                  AND external_created_at >= :startAt
                  AND external_created_at < :endAt
                  AND canonical_status IN (%s)
                """.formatted(ANALYTICS_REVENUE_STATUSES), tenantId, start, end);
    }

    private BigDecimal costOfGoods(String tenantId, Instant start, Instant end) {
        List<BigDecimal> costs = jdbcClient.sql("""
                SELECT oi.quantity, p.attributes_json
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id
                LEFT JOIN products p ON p.id = oi.product_id AND p.tenant_id = oi.tenant_id
                WHERE o.tenant_id = :tenantId
                  AND o.deleted_at IS NULL
                  AND o.external_created_at >= :startAt
                  AND o.external_created_at < :endAt
                  AND o.canonical_status IN (%s)
                """.formatted(ANALYTICS_REVENUE_STATUSES))
                .param("tenantId", tenantId)
                .param("startAt", Timestamp.from(start))
                .param("endAt", Timestamp.from(end))
                .query((rs, rowNum) -> productCost(rs.getString("attributes_json"))
                        .multiply(BigDecimal.valueOf(rs.getLong("quantity"))))
                .list();
        return costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal productCost(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return BigDecimal.ZERO;
        try {
            JsonNode root = objectMapper.readTree(attributesJson);
            if (root.isTextual()) root = objectMapper.readTree(root.asText());
            JsonNode value = root.path("costPrice");
            return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText("0"));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal amount(String sql, String tenantId, Instant start, Instant end) {
        BigDecimal value = jdbcClient.sql(sql)
                .param("tenantId", tenantId)
                .param("startAt", Timestamp.from(start))
                .param("endAt", Timestamp.from(end))
                .query(BigDecimal.class)
                .single();
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, String tenantId, Instant start, Instant end) {
        Long value = jdbcClient.sql(sql)
                .param("tenantId", tenantId)
                .param("startAt", Timestamp.from(start))
                .param("endAt", Timestamp.from(end))
                .query(Long.class)
                .single();
        return value == null ? 0 : value;
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal growth(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return current.signum() == 0 ? BigDecimal.ZERO.setScale(2) : BigDecimal.valueOf(100).setScale(2);
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static PeriodRange periodRange(String rawPeriod, Instant now) {
        String period = rawPeriod == null ? "this-month" : rawPeriod.trim().toLowerCase(Locale.ROOT);
        ZonedDateTime current = now.atZone(BUSINESS_ZONE);
        ZonedDateTime start;
        ZonedDateTime end;
        ZonedDateTime previousStart;
        ZonedDateTime previousBoundaryEnd;

        switch (period) {
            case "this-month" -> {
                start = current.withDayOfMonth(1).toLocalDate().atStartOfDay(BUSINESS_ZONE);
                end = current;
                previousStart = start.minusMonths(1);
                previousBoundaryEnd = start;
            }
            case "last-month" -> {
                end = current.withDayOfMonth(1).toLocalDate().atStartOfDay(BUSINESS_ZONE);
                start = end.minusMonths(1);
                previousStart = start.minusMonths(1);
                previousBoundaryEnd = start;
            }
            case "q3", "this-quarter" -> {
                int firstMonth = ((current.getMonthValue() - 1) / 3) * 3 + 1;
                start = current.withMonth(firstMonth).withDayOfMonth(1)
                        .toLocalDate().atStartOfDay(BUSINESS_ZONE);
                end = current;
                previousStart = start.minusMonths(3);
                previousBoundaryEnd = start;
            }
            case "full-year", "this-year" -> {
                start = current.with(TemporalAdjusters.firstDayOfYear())
                        .toLocalDate().atStartOfDay(BUSINESS_ZONE);
                end = current;
                previousStart = start.minusYears(1);
                previousBoundaryEnd = start;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANALYTICS_PERIOD",
                    "Khoảng thời gian phân tích không hợp lệ.");
        }

        Duration elapsed = Duration.between(start, end);
        ZonedDateTime previousEnd = previousStart.plus(elapsed);
        if (previousEnd.isAfter(previousBoundaryEnd)) previousEnd = previousBoundaryEnd;
        return new PeriodRange(start.toInstant(), end.toInstant(),
                previousStart.toInstant(), previousEnd.toInstant());
    }

    private record PeriodRange(
            Instant start,
            Instant end,
            Instant previousStart,
            Instant previousEnd) {
    }

    private record RevenueOrder(Instant createdAt, BigDecimal amount) {
    }

    private enum ChartGranularity {
        DAY,
        MONTH,
        QUARTER
    }
}
