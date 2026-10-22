package com.inn.cafe.Utils;

/**
 * Provider-agnostic delivery adapter for outbound communication.
 *
 * The platform must be able to say, HONESTLY, whether a message actually went
 * out. This interface is the clean seam the spec asks for: WhatsApp/SMS
 * providers can be added later as additional @Component implementations —
 * the campaign and automation services depend only on this interface, never
 * on a concrete provider, and NO provider is ever faked.
 *
 * Contract:
 *  - {@link #configured()} must reflect REAL configuration state (e.g. valid
 *    SMTP credentials present). It is surfaced to admins in the UI.
 *  - {@link #send(String, String, String)} throws on failure; callers record
 *    the outcome in campaign_events / automation_runs.
 */
public interface NotificationChannel {
    /** Stable identifier, e.g. "EMAIL". */
    String id();

    /** True only when the provider is genuinely configured and usable. */
    boolean configured();

    /** Send a message; throws Exception with a human-readable cause on failure. */
    void send(String to, String subject, String body) throws Exception;
}
