package com.fitnesscoaching.platform.modules.student.application.port.in;

import java.util.UUID;

/** Published Student-module query for cross-module capability checks. */
public interface StudentCapabilityQuery {
    boolean hasProfile(UUID userId);
}
