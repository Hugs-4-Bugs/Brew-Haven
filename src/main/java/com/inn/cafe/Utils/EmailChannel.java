package com.inn.cafe.Utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Email channel — wraps the EXISTING EmailUtils (same SMTP stack used for
 * account-approval notifications). "Configured" is detected honestly: the
 * spring.mail.username property must be set to something other than the
 * packaged placeholder default. In the dev workspace no SMTP credentials
 * exist, so this channel reports configured() == false and campaign sends
 * fall back to the LogChannel (events recorded, nothing faked).
 */
@Slf4j
@Component
public class EmailChannel implements NotificationChannel {

    private final EmailUtils emailUtils;
    private final String mailUsername;
    private final String mailPassword;

    public EmailChannel(EmailUtils emailUtils,
                        @Value("${spring.mail.username:}") String mailUsername,
                        @Value("${spring.mail.password:}") String mailPassword) {
        this.emailUtils = emailUtils;
        this.mailUsername = mailUsername == null ? "" : mailUsername.trim();
        this.mailPassword = mailPassword == null ? "" : mailPassword.trim();
    }

    @Override
    public String id() {
        return "EMAIL";
    }

    @Override
    public boolean configured() {
        return !mailUsername.isEmpty()
                && !"your_email".equalsIgnoreCase(mailUsername)
                && !mailPassword.isEmpty()
                && !"your_email_app_password".equalsIgnoreCase(mailPassword);
    }

    @Override
    public void send(String to, String subject, String body) throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Email channel is not configured (missing spring.mail credentials)");
        }
        emailUtils.sendSimpleMessage(to, subject, body, Collections.emptyList());
    }
}
