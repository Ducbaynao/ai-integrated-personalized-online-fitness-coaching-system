package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.MailSendException;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpVerificationEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpVerificationEmailSender emailSender = new SmtpVerificationEmailSender(mailSender, "sender@example.com");

    @Test
    @DisplayName("Sends verification email via JavaMailSender with deep link and manual token")
    void sendsVerificationEmailViaJavaMailSender() {
        String recipient = "student@example.com";
        String token = "raw-verification-token-12345";

        emailSender.sendVerificationEmail(recipient, token);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("sender@example.com");
        assertThat(sent.getTo()).containsExactly("student@example.com");
        assertThat(sent.getSubject()).isEqualTo("Verify your email - AI Fitness Coaching");
        assertThat(sent.getText()).contains("ai-fitness-coaching://verify-email?token=" + token);
        assertThat(sent.getText()).contains(token);
        assertThat(sent.getText()).contains("expires in 24 hours");
    }

    @Test
    void failureDoesNotLeakProviderMessageOrToken() {
        doThrow(new MailSendException("secret token and provider credentials"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        assertThatThrownBy(() -> emailSender.sendVerificationEmail("student@example.com", "raw-token"))
                .isInstanceOf(MailSendException.class)
                .hasMessage("Verification SMTP delivery failed; check SMTP availability, TLS and credentials.")
                .hasNoCause();
    }

    @Test
    void stalledSmtpServerTimesOutAndReportsSafeFailure() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var peer = executor.submit(() -> {
                try (var connection = server.accept()) {
                    // Accept TCP but deliberately do not send an SMTP greeting.
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            JavaMailSenderImpl transport = new JavaMailSenderImpl();
            transport.setHost(server.getInetAddress().getHostAddress());
            transport.setPort(server.getLocalPort());
            transport.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "500");
            transport.getJavaMailProperties().setProperty("mail.smtp.timeout", "100");
            transport.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "500");
            try {
                assertThatThrownBy(() -> new SmtpVerificationEmailSender(transport, "sender@example.com")
                        .sendVerificationEmail("student@example.com", "test-only-token"))
                        .isInstanceOf(MailSendException.class)
                        .hasMessageContaining("Verification SMTP delivery failed")
                        .hasNoCause();
            } finally {
                releaseServer.countDown();
            }
            peer.get(5, TimeUnit.SECONDS);
        }
    }
}
