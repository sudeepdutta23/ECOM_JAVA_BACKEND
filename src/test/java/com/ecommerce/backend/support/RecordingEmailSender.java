package com.ecommerce.backend.support;

import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.common.mail.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test double that keeps sent emails in memory instead of delivering them.
 *
 * <p>Thread-safe because emails are sent from the async pool. All integration tests share one
 * application context, so tests filter by their own unique recipient instead of clearing.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sentTo(String recipient) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(recipient)).toList();
    }

    /** Extracts the one-time token from an email link. */
    public static String tokenFrom(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("No token link in email: " + message.subject());
        }
        return matcher.group(1);
    }
}
