package com.fitnesscoaching.platform.modules.student;

import java.util.UUID;

/** Student Profile owns current, nullable profile fields. Caller fences the request. */
public interface StudentProfileAccountErasure {
    void minimizeCurrentProfile(UUID userId);
}
