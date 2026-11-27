package com.fitnesscoaching.platform.modules.auth.adapter.out.email;

import com.fitnesscoaching.platform.modules.auth.application.port.out.VerificationEmailPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationEmailConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(VerificationEmailConfiguration.class);

    @Test
    void devMailpitHasOneSenderAndFiniteTimeouts() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasSingleBean(VerificationEmailPort.class);
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(sender.getHost()).isEqualTo("localhost");
                    assertThat(sender.getPort()).isEqualTo(1025);
                    assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false")
                            .containsEntry("mail.smtp.starttls.enable", "false")
                            .containsEntry("mail.smtp.connectiontimeout", "5000")
                            .containsEntry("mail.smtp.timeout", "5000")
                            .containsEntry("mail.smtp.writetimeout", "5000");
                });
    }

    @Test
    void realSmtpWorksOutsideDevWithRequiredStarttls() {
        smtp().run(context -> {
            assertThat(context).hasSingleBean(VerificationEmailPort.class);
            JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getHost()).isEqualTo("smtp.example.com");
            assertThat(sender.getPort()).isEqualTo(587);
            assertThat(sender.getUsername()).isEqualTo("smtp-user");
            assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .containsEntry("mail.debug", "false");
        });
    }

    @Test
    void realSmtpInDevDoesNotCreateDuplicateMailpitSender() {
        smtp().withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context).hasSingleBean(VerificationEmailPort.class));
    }

    @Test
    void implicitSslIsSupported() {
        smtp().withPropertyValues("spring.mail.port=465", "SMTP_STARTTLS=false", "SMTP_SSL=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(VerificationEmailPort.class);
                    assertThat(context.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                            .containsEntry("mail.smtp.ssl.enable", "true")
                            .containsEntry("mail.smtp.starttls.enable", "false");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFICATION_EMAIL_MODE=unknown", "spring.mail.host=", "spring.mail.port=0",
            "spring.mail.port=65536", "VERIFICATION_EMAIL_FROM=invalid", "VERIFICATION_EMAIL_FROM=sender@fitnesscoaching.local",
            "spring.mail.username=", "spring.mail.password=", "SMTP_SSL=true", "SMTP_STARTTLS=false",
            "SMTP_CONNECTION_TIMEOUT_MS=0", "SMTP_READ_TIMEOUT_MS=-1", "SMTP_WRITE_TIMEOUT_MS=0"})
    void invalidRealSmtpFailsStartup(String invalidSetting) {
        smtp().withPropertyValues(invalidSetting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingRealSmtpDoesNotFallBackToMailpitEvenInDev() {
        runner.withPropertyValues("spring.profiles.active=dev", "VERIFICATION_EMAIL_MODE=smtp")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mailpitCannotBeEnabledOutsideDev() {
        runner.withPropertyValues("VERIFICATION_EMAIL_MODE=mailpit")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void noModeOutsideDevFailsClearly() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Set VERIFICATION_EMAIL_MODE to smtp (or mailpit under dev).");
        });
    }

    @Test
    void testAndDevProfilesDoNotLoadNetworkSender() {
        runner.withPropertyValues("spring.profiles.active=dev,test")
                .run(context -> assertThat(context).doesNotHaveBean(VerificationEmailPort.class));
    }

    @Test
    void unauthenticatedTlsRelayCanBeSelectedExplicitly() {
        runner.withPropertyValues("VERIFICATION_EMAIL_MODE=smtp", "spring.mail.host=relay.example.com",
                        "spring.mail.port=587", "VERIFICATION_EMAIL_FROM=sender@example.com", "SMTP_AUTH=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(VerificationEmailPort.class);
                    assertThat(context.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                            .containsEntry("mail.smtp.auth", "false");
                });
    }

    private ApplicationContextRunner smtp() {
        return runner.withPropertyValues("VERIFICATION_EMAIL_MODE=smtp", "spring.mail.host=smtp.example.com",
                "spring.mail.port=587", "VERIFICATION_EMAIL_FROM=sender@example.com",
                "spring.mail.username=smtp-user", "spring.mail.password=test-only-placeholder");
    }
}
