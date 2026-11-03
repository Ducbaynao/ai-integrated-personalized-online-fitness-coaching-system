package com.fitnesscoaching.platform.modules.user.application.port.out;

import java.util.UUID;

/** Required Trainer-owned minimization inside Account's fenced transaction. */
public interface TrainerProfileAccountErasure {
    void minimizeCurrentProfile(UUID userId);
}
