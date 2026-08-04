package com.backend.service.impl;

import com.backend.config.StaffMailProperties;
import com.backend.service.ApiException;
import com.backend.service.StaffCredentialEmailService;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpStaffCredentialEmailService implements StaffCredentialEmailService {

    private final JavaMailSender mailSender;
    private final StaffMailProperties properties;

    public SmtpStaffCredentialEmailService(
            JavaMailSender mailSender,
            StaffMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendTemporaryPassword(
            String email,
            String displayName,
            String tenantName,
            String temporaryPassword
    ) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject("[OmnichannelPOS] Tài khoản nhân viên CSKH");
        message.setText("""
                Xin chào %s,

                Tài khoản nhân viên CSKH của bạn tại %s đã được tạo.

                Email đăng nhập: %s
                Mật khẩu tạm thời: %s
                Đăng nhập tại: %s

                Vui lòng đổi mật khẩu ngay trong lần đăng nhập đầu tiên và không chia sẻ email này.

                OmnichannelPOS
                """.formatted(
                displayName,
                tenantName,
                email,
                temporaryPassword,
                properties.loginUrl()));
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "STAFF_EMAIL_DELIVERY_FAILED",
                    "Không thể gửi mật khẩu đến email nhân viên. Tài khoản chưa được tạo.");
        }
    }
}
