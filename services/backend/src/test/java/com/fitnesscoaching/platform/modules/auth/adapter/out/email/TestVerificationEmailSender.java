package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only capturing email sender.
 * Explicitly active under the 'test' profile to satisfy VerificationEmailPort dependency in tests.
 */
@Component
@Profile("test")
public class TestVerificationEmailSender implements VerificationEmailPort {

    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    @Override
    public void sendVerificationEmail(String recipientEmail, String rawVerificationToken) {
        tokens.put(recipientEmail, rawVerificationToken);
    }

    public String getLastTokenFor(String recipientEmail) {
        return tokens.get(recipientEmail);
    }

    public void clear() {
        tokens.clear();
    }
}
