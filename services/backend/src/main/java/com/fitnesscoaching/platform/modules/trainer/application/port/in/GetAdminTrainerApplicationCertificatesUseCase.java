package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerCertificateInfo;

import java.util.List;
import java.util.UUID;

public interface GetAdminTrainerApplicationCertificatesUseCase {

    List<TrainerCertificateInfo> getApplicationCertificates(UUID adminUserId, UUID applicationId);
}
