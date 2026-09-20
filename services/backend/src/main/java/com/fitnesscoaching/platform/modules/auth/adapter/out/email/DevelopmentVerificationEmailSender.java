package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Development-only verification email sender.
 * Explicitly active only under the 'dev' profile.
 * Sends email via SMTP (e.g. to Mailpit) containing deep link and manual verification token.
 * Production/default configuration does NOT load this bean to prevent silent discarding of verification emails.
 */
@Component
@Profile("dev")
public class DevelopmentVerificationEmailSender implements VerificationEmailPort {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentVerificationEmailSender.class);
    private static final String DEFAULT_FROM = "no-reply@fitnesscoaching.local";

    private final JavaMailSender mailSender;

    public DevelopmentVerificationEmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendVerificationEmail(String recipientEmail, String rawVerificationToken) {
        // Safe development dispatch: NEVER log the plaintext token in logs
        log.info("Dispatching email verification via SMTP to recipient: {}", maskEmail(recipientEmail));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(DEFAULT_FROM);
        message.setTo(recipientEmail);
        message.setSubject("Verify your email - AI Fitness Coaching");
        message.setText("""
                Hello,

                Thank you for registering with AI Fitness Coaching!

                Please verify your email address by tapping the link below on your mobile device:
                ai-fitness-coaching://verify-email?token=%s

                Alternatively, you can manually enter the verification token in the app:
                %s

                This verification token expires in 24 hours.
                """.formatted(rawVerificationToken, rawVerificationToken));

        mailSender.send(message);
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
