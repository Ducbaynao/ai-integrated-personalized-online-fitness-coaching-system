package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP transport shared by explicit real SMTP and development Mailpit configuration. */
public class SmtpVerificationEmailSender implements VerificationEmailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpVerificationEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationEmail(String recipientEmail, String rawVerificationToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipientEmail);
        message.setSubject("Verify your email - AI Fitness Coaching");
        message.setText("""
                Hello,

                Thank you for registering with AI Fitness Coaching!

                Enter the verification token below in the app to verify your email address:
                %s

                If your installed app supports this link, you may also open:
                ai-fitness-coaching://verify-email?token=%s

                This verification token expires in 24 hours.
                """.formatted(rawVerificationToken, rawVerificationToken));

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            // Provider exceptions can retain the entire failed message, including its token.
            // The application service logs this exception after commit: expose only a safe summary.
            throw new MailSendException("Verification SMTP delivery failed; check SMTP availability, TLS and credentials.");
        }
        log.info("Verification email accepted by SMTP for recipient: {}", maskEmail(recipientEmail));
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
