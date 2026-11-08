package com.fitnesscoaching.platform.modules.media.application.port.in;

import java.util.List;
import java.util.UUID;

public interface MediaQueryPort {

    /**
     * Checks whether all specified media IDs exist in active storage, belong to the owner,
     * and are not soft-deleted.
     */
    boolean allMediaExistAndOwnedBy(List<UUID> mediaIds, UUID ownerUserId);

    /**
     * Checks whether all specified media IDs exist in active storage, belong to the owner,
     * are not soft-deleted, AND have completed security malware scanning with scan_status = 'CLEAN'.
     */
    boolean allMediaExistOwnedAndClean(List<UUID> mediaIds, UUID ownerUserId);
}
