package com.fitnesscoaching.platform.modules.media.application.port.in;

import java.util.List;
import java.util.UUID;

public interface MediaQueryPort {

    boolean allMediaExistAndOwnedBy(List<UUID> mediaIds, UUID ownerUserId);
}
