package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentInfo;

import java.util.UUID;

public interface UploadTrainerVerificationDocumentUseCase {

    TrainerVerificationDocumentInfo uploadDocument(UUID trainerId, String filename, String declaredContentType, byte[] bytes);
}
