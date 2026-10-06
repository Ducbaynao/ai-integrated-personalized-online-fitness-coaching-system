package com.fitnesscoaching.platform.modules.trainer.application.model;

import java.util.UUID;

public record TrainerDirectoryItem(UUID trainerId, String displayName) {
}
