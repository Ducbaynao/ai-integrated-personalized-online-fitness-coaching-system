package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only verification email sender.
 * Explicitly active only under the 'dev' profile.
 * Production/default configuration does NOT load this bean to prevent silent discarding of verification emails.
 */
@Component
@Profile("dev")
public class DevelopmentVerificationEmailSender implements VerificationEmailPort {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentVerificationEmailSender.class);

    @Override
    public void sendVerificationEmail(String recipientEmail, String rawVerificationToken) {
        // Safe development simulation: NEVER log the plaintext token in logs
        log.info("Simulating email verification dispatch to recipient: {}", maskEmail(recipientEmail));
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        String name = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (name.length() <= 2) {
            return name.charAt(0) + "***" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
    }
}
