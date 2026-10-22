package com.inn.cafe.Utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback channel that is ALWAYS available and NEVER pretends to send.
 * When no real provider is configured, campaign delivery resolves to this
 * channel: the send is logged and a campaign_event with channel=NONE is
 * recorded, so attribution and audit trails stay complete while the admin
 * UI honestly shows "No provider configured — events recorded only".
 */
@Slf4j
@Component
public class LogChannel implements NotificationChannel {

    @Override
    public String id() {
        return "NONE";
    }

    @Override
    public boolean configured() {
        return true; // always usable — it records rather than delivers
    }

    @Override
    public void send(String to, String subject, String body) {
        log.info("[NotificationChannel:NONE] recorded (no provider configured) to={} subject={}", to, subject);
    }
}
