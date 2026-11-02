package com.fitnesscoaching.platform.modules.progress;

import java.util.UUID;

/** Progress-owned connection identifiers; measurement values/media need a separate A01 design. */
public interface ProgressAccountErasure {
    void minimizeOwnedConnectionIdentifiers(UUID userId);
}
