package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentContent;

import java.util.UUID;

public interface DownloadAdminTrainerApplicationDocumentUseCase {

    TrainerVerificationDocumentContent downloadApplicationDocument(UUID adminUserId, UUID applicationId, UUID mediaId);
}
