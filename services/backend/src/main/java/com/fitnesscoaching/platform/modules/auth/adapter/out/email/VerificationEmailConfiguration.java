package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class VerificationEmailConfiguration {
    private static final String PREFIX = "auth.verification-email.";

    @Bean
    JavaMailSender verificationMailSender(Environment env) {
        String mode = env.getProperty("VERIFICATION_EMAIL_MODE", env.getProperty(PREFIX + "mode", ""));
        boolean mailpit = "mailpit".equals(mode);
        require(mailpit || "smtp".equals(mode), "Set VERIFICATION_EMAIL_MODE to smtp (or mailpit under dev).");
        require(!mailpit || env.acceptsProfiles(Profiles.of("dev")), "Mailpit verification email requires the dev profile.");

        String host = env.getProperty("spring.mail.host", mailpit ? "localhost" : "");
        int port = env.getProperty("spring.mail.port", Integer.class, mailpit ? 1025 : 0);
        String from = env.getProperty("VERIFICATION_EMAIL_FROM", env.getProperty(PREFIX + "from", mailpit ? "no-reply@fitnesscoaching.local" : ""));
        require(StringUtils.hasText(host) && !host.contains("://") && !host.matches(".*\\s+.*"), "Set a valid SPRING_MAIL_HOST hostname.");
        require(port > 0 && port <= 65535, "Set SPRING_MAIL_PORT between 1 and 65535.");
        validateFrom(from, mailpit);

        boolean auth = env.getProperty("SMTP_AUTH", Boolean.class, env.getProperty(PREFIX + "auth", Boolean.class, !mailpit));
        boolean starttls = env.getProperty("SMTP_STARTTLS", Boolean.class, env.getProperty(PREFIX + "starttls", Boolean.class, !mailpit));
        boolean ssl = env.getProperty("SMTP_SSL", Boolean.class, env.getProperty(PREFIX + "ssl", Boolean.class, false));
        require(!(starttls && ssl), "Choose STARTTLS or SSL, not both.");
        require(mailpit || starttls || ssl, "Real SMTP requires STARTTLS or SSL.");
        String username = env.getProperty("spring.mail.username", "");
        String password = env.getProperty("spring.mail.password", "");
        require(!auth || (StringUtils.hasText(username) && StringUtils.hasText(password)), "SMTP auth requires SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD.");

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        if (auth) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(auth));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.debug", "false");
        setTimeout(properties, env, "SMTP_CONNECTION_TIMEOUT_MS", "connectiontimeout");
        setTimeout(properties, env, "SMTP_READ_TIMEOUT_MS", "timeout");
        setTimeout(properties, env, "SMTP_WRITE_TIMEOUT_MS", "writetimeout");
        return sender;
    }

    @Bean
    VerificationEmailPort verificationEmailPort(JavaMailSender verificationMailSender, Environment env) {
        return new SmtpVerificationEmailSender(verificationMailSender,
                env.getProperty("VERIFICATION_EMAIL_FROM", env.getProperty(PREFIX + "from", "no-reply@fitnesscoaching.local")));
    }

    private static void setTimeout(Properties properties, Environment env, String key, String smtpKey) {
        int value = env.getProperty(key, Integer.class, 5000);
        require(value > 0, "SMTP " + key + " must be a positive number of milliseconds.");
        properties.setProperty("mail.smtp." + smtpKey, Integer.toString(value));
    }

    private static void validateFrom(String from, boolean mailpit) {
        try {
            InternetAddress address = new InternetAddress(from, true);
            address.validate();
            require(address.getPersonal() == null && from.equals(address.getAddress()) && from.contains("@")
                    && !from.contains("\r") && !from.contains("\n"), "Set VERIFICATION_EMAIL_FROM to one valid email address.");
            require(mailpit || !from.endsWith(".local"), "Real SMTP requires a provider-authorized VERIFICATION_EMAIL_FROM.");
        } catch (AddressException exception) {
            throw new IllegalStateException("Set VERIFICATION_EMAIL_FROM to one valid email address.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
