package com.fitnesscoaching.platform.modules.user.application.port.out;

import com.fitnesscoaching.platform.modules.user.application.model.UserProfileUpdateData;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsData;

import java.util.UUID;

public interface UserUpdatePort {

    void updateUserProfile(UUID userId, UserProfileUpdateData profileData);

    void upsertUserSettings(UUID userId, UserSettingsData settingsData);
}
