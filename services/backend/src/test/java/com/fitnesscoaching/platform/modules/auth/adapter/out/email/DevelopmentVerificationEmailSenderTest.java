package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DevelopmentVerificationEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final DevelopmentVerificationEmailSender emailSender = new DevelopmentVerificationEmailSender(mailSender);

    @Test
    @DisplayName("Sends verification email via JavaMailSender with deep link and manual token")
    void sendsVerificationEmailViaJavaMailSender() {
        String recipient = "student@example.com";
        String token = "raw-verification-token-12345";

        emailSender.sendVerificationEmail(recipient, token);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("student@example.com");
        assertThat(sent.getSubject()).isEqualTo("Verify your email - AI Fitness Coaching");
        assertThat(sent.getText()).contains("ai-fitness-coaching://verify-email?token=" + token);
        assertThat(sent.getText()).contains(token);
    }
}
