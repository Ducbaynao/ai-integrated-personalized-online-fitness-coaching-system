package com.fitnesscoaching.platform.modules.auth.application.port.out;

public interface VerificationEmailPort {

    void sendVerificationEmail(String recipientEmail, String rawVerificationToken);
}
