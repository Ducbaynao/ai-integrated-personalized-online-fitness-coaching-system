package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerCertificateInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdminTrainerCertificateResponse(
        UUID id,
        String name,
        String issuer,
        String credentialNumber,
        LocalDate issuedOn,
        LocalDate expiresOn,
        String verificationStatus,
        UUID documentMediaId,
        Instant createdAt
) {
    public static AdminTrainerCertificateResponse fromDomain(TrainerCertificateInfo cert) {
        return new AdminTrainerCertificateResponse(
                cert.id(),
                cert.name(),
                cert.issuer(),
                cert.credentialNumber(),
                cert.issuedOn(),
                cert.expiresOn(),
                cert.verificationStatus(),
                cert.documentMediaId(),
                cert.createdAt()
        );
    }
}
