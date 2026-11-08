package com.fitnesscoaching.platform.modules.trainer.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TrainerCertificateInfo(
        UUID id,
        String name,
        String issuer,
        String credentialNumber,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String verificationStatus,
        UUID documentMediaId,
        Instant createdAt
) {}
