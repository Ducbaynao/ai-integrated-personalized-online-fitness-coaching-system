package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentInfo;

import java.util.List;
import java.util.UUID;

public interface GetAdminTrainerApplicationDocumentsUseCase {

    List<TrainerVerificationDocumentInfo> getApplicationDocuments(UUID adminUserId, UUID applicationId);
}
