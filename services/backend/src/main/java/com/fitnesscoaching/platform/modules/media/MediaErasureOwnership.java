package com.fitnesscoaching.platform.modules.media;

import java.util.UUID;

/** Media-owned proof that a Chat object has no non-Chat references. */
public interface MediaErasureOwnership {
    boolean hasNoNonChatReferences(UUID mediaId);
}
