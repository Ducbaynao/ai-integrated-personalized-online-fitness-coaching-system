package com.fitnesscoaching.platform.modules.trainer.application.port.out;

import java.util.List;
import java.util.UUID;

public interface TrainerCertificatePort {

    boolean allCertificatesExistAndBelongToTrainer(List<UUID> certificateIds, UUID trainerId);
}
