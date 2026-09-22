package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DecideTrainerApplicationRequest(
        @NotBlank(message = "Decision is required")
        String decision,
        @Size(max = 2000, message = "Rejection reason cannot exceed 2000 characters")
        String rejectionReason,
        @Size(max = 2000, message = "Review notes cannot exceed 2000 characters")
        String reviewNotes
) {
    public DecideTrainerApplicationCommand toCommand(UUID adminUserId, UUID applicationId) {
        return new DecideTrainerApplicationCommand(
                adminUserId,
                applicationId,
                decision,
                rejectionReason,
                reviewNotes
        );
    }
}
