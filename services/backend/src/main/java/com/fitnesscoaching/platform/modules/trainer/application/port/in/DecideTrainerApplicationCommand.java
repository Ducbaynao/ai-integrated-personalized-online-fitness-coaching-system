package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import java.util.UUID;

public record DecideTrainerApplicationCommand(
        UUID adminUserId,
        UUID applicationId,
        String decision,
        String rejectionReason,
        String reviewNotes
) {
}
