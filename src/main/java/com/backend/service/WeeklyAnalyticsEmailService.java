package com.backend.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.backend.dto.RevenueAnalyticsResponse;

@Service
@ConditionalOnProperty(
        name = "app.analytics.weekly-email-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WeeklyAnalyticsEmailService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyAnalyticsEmailService.class);
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");

    private final JdbcClient jdbcClient;
    private final AnalyticsService analyticsService;
    private final JavaMailSender mailSender;
    private final String sender;

    public WeeklyAnalyticsEmailService(
            JdbcClient jdbcClient,
            AnalyticsService analyticsService,
            JavaMailSender mailSender,
            @Value("${app.analytics.mail.from:${spring.mail.username:}}") String sender) {
        this.jdbcClient = jdbcClient;
        this.analyticsService = analyticsService;
        this.mailSender = mailSender;
        this.sender = sender;
    }

    @Scheduled(
            cron = "${app.analytics.weekly-email-cron:0 0 8 * * MON}",
            zone = "${app.analytics.weekly-email-zone:Asia/Ho_Chi_Minh}")
    public void sendPreviousWeekReports() {
        ZonedDateTime thisMonday = LocalDate.now(AnalyticsService.BUSINESS_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(AnalyticsService.BUSINESS_ZONE);
        Instant periodEnd = thisMonday.toInstant();
        Instant periodStart = thisMonday.minusWeeks(1).toInstant();
        Instant previousStart = thisMonday.minusWeeks(2).toInstant();
        LocalDate weekStart = thisMonday.minusWeeks(1).toLocalDate();

        List<TenantRecipient> tenants = jdbcClient.sql("""
                SELECT id, tenant_name, contact_email
                FROM tenants
                WHERE status IN ('TRIAL', 'ACTIVE') AND deleted_at IS NULL
                ORDER BY id
                """)
                .query((rs, rowNum) -> new TenantRecipient(
                        rs.getString("id"),
                        rs.getString("tenant_name"),
                        rs.getString("contact_email")))
                .list();

        for (TenantRecipient tenant : tenants) {
            for (String recipient : recipients(tenant)) {
                sendOne(tenant, recipient, weekStart, periodStart, periodEnd, previousStart);
            }
        }
    }

    private Set<String> recipients(TenantRecipient tenant) {
        Set<String> recipients = new LinkedHashSet<>();
        if (hasText(tenant.contactEmail())) {
            recipients.add(tenant.contactEmail().trim().toLowerCase(Locale.ROOT));
            return recipients;
        }

        jdbcClient.sql("""
                SELECT DISTINCT tu.email
                FROM tenant_users tu
                JOIN tenant_user_roles tur
                  ON tur.tenant_user_id = tu.id AND tur.tenant_id = tu.tenant_id
                JOIN roles r ON r.id = tur.role_id
                WHERE tu.tenant_id = :tenantId
                  AND tu.status = 'ACTIVE'
                  AND tu.deleted_at IS NULL
                  AND r.role_code = 'TENANT_MANAGER'
                ORDER BY tu.email
                """)
                .param("tenantId", tenant.id())
                .query(String.class)
                .list()
                .stream()
                .filter(WeeklyAnalyticsEmailService::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(recipients::add);
        return recipients;
    }

    private void sendOne(
            TenantRecipient tenant,
            String recipient,
            LocalDate weekStart,
            Instant periodStart,
            Instant periodEnd,
            Instant previousStart) {
        if (!claim(tenant.id(), weekStart, recipient)) return;

        try {
            RevenueAnalyticsResponse report = analyticsService.revenueForRange(
                    tenant.id(), periodStart, periodEnd, previousStart, periodStart);
            SimpleMailMessage message = new SimpleMailMessage();
            if (hasText(sender)) message.setFrom(sender);
            message.setTo(recipient);
            message.setSubject("[OmnichannelPOS] Phân tích tuần - " + tenant.name());
            message.setText(reportText(tenant.name(), weekStart, report));
            mailSender.send(message);
            markSent(tenant.id(), weekStart, recipient);
        } catch (Exception exception) {
            markFailed(tenant.id(), weekStart, recipient, exception.getMessage());
            log.error("Weekly analytics email failed for tenant {} recipient {}",
                    tenant.id(), recipient, exception);
        }
    }

    private boolean claim(String tenantId, LocalDate weekStart, String recipient) {
        Integer inserted = jdbcClient.sql("""
                INSERT INTO weekly_analytics_email_deliveries (
                  tenant_id, week_start, recipient_email, status
                ) VALUES (:tenantId, :weekStart, :recipient, 'SENDING')
                ON CONFLICT (tenant_id, week_start, recipient_email) DO NOTHING
                RETURNING 1
                """)
                .param("tenantId", tenantId)
                .param("weekStart", Date.valueOf(weekStart))
                .param("recipient", recipient)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return inserted != null;
    }

    private void markSent(String tenantId, LocalDate weekStart, String recipient) {
        jdbcClient.sql("""
                UPDATE weekly_analytics_email_deliveries
                SET status = 'SENT', sent_at = :sentAt, last_error = NULL,
                    updated_at = :sentAt
                WHERE tenant_id = :tenantId
                  AND week_start = :weekStart
                  AND recipient_email = :recipient
                """)
                .param("sentAt", Timestamp.from(Instant.now()))
                .param("tenantId", tenantId)
                .param("weekStart", Date.valueOf(weekStart))
                .param("recipient", recipient)
                .update();
    }

    private void markFailed(String tenantId, LocalDate weekStart, String recipient, String error) {
        jdbcClient.sql("""
                UPDATE weekly_analytics_email_deliveries
                SET status = 'FAILED', last_error = :error, updated_at = :updatedAt
                WHERE tenant_id = :tenantId
                  AND week_start = :weekStart
                  AND recipient_email = :recipient
                """)
                .param("error", abbreviate(error))
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("tenantId", tenantId)
                .param("weekStart", Date.valueOf(weekStart))
                .param("recipient", recipient)
                .update();
    }

    private static String reportText(
            String tenantName,
            LocalDate weekStart,
            RevenueAnalyticsResponse report) {
        LocalDate weekEnd = weekStart.plusDays(6);
        return """
                Xin chào %s,

                Báo cáo tài chính và tăng trưởng tuần %s - %s:

                Tổng doanh thu: %s
                Tổng chi phí: %s
                Lợi nhuận ròng: %s
                Tỷ suất lợi nhuận: %s%%
                Tăng trưởng so với tuần trước: %s%%

                OmnichannelPOS
                """.formatted(
                tenantName,
                formatDate(weekStart),
                formatDate(weekEnd),
                formatMoney(report.totalRevenue()),
                formatMoney(report.totalCost()),
                formatMoney(report.netProfit()),
                report.profitMargin().toPlainString(),
                report.growthPercent().toPlainString());
    }

    private static String formatMoney(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(VIETNAMESE).format(value);
    }

    private static String formatDate(LocalDate value) {
        return "%02d/%02d/%d".formatted(value.getDayOfMonth(), value.getMonthValue(), value.getYear());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String abbreviate(String error) {
        if (!hasText(error)) return "Không xác định được lỗi gửi email";
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private record TenantRecipient(String id, String name, String contactEmail) {
    }
}
