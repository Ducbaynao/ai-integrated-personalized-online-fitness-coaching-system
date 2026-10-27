package com.fitnesscoaching.platform.modules.notification;

import java.util.List;
import java.util.UUID;

/** Notification-owned, actor-scoped export of preferences; never exposes device tokens. */
public interface PushPreferenceQuery {
    List<PushPreferenceService.Preference> preferencesForExport(UUID actor);
}
