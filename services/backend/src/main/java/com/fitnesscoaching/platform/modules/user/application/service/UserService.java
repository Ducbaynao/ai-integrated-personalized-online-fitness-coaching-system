package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserProfileUpdateData;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsData;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.GetCurrentUserUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateUserSettingsCommand;
import com.fitnesscoaching.platform.modules.user.application.port.out.UserUpdatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class UserService implements GetCurrentUserUseCase, UpdateCurrentUserUseCase {

    private final CurrentUserQuery currentUserQuery;
    private final UserUpdatePort userUpdatePort;
    private final Clock clock;

    public UserService(
            CurrentUserQuery currentUserQuery,
            UserUpdatePort userUpdatePort,
            Clock clock
    ) {
        this.currentUserQuery = currentUserQuery;
        this.userUpdatePort = userUpdatePort;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentUserView getCurrentUser(UUID userId) {
        return currentUserQuery.findCurrentUserById(userId)
                .orElseThrow(() -> new UserNotFoundException("Authenticated user not found: " + userId));
    }

    @Override
    public CurrentUserView updateCurrentUser(UpdateCurrentUserCommand command) {
        UUID userId = command.userId();
        CurrentUserView current = currentUserQuery.findCurrentUserById(userId)
                .orElseThrow(() -> new UserNotFoundException("Authenticated user not found: " + userId));

        Instant now = Instant.now(clock);

        boolean profileNeedsUpdate = command.displayName().isSpecified()
                || command.phoneNumber().isSpecified()
                || command.preferredLocale().isSpecified()
                || command.timezone().isSpecified();

        boolean settingsNeedsUpdate = command.settings().isSpecified()
                && command.settings().value() != null
                && command.settings().value().hasUpdates();

        if (!profileNeedsUpdate && !settingsNeedsUpdate) {
            return current;
        }

        if (profileNeedsUpdate) {
            String newDisplayName = command.displayName().isSpecified() ? command.displayName().value() : null;
            boolean phoneSpecified = command.phoneNumber().isSpecified();
            String newPhone = phoneSpecified ? command.phoneNumber().value() : null;
            String newLocale = command.preferredLocale().isSpecified() ? command.preferredLocale().value() : null;
            String newTimezone = command.timezone().isSpecified() ? command.timezone().value() : null;

            userUpdatePort.updateUserProfile(
                    userId,
                    new UserProfileUpdateData(
                            newDisplayName,
                            phoneSpecified,
                            newPhone,
                            newLocale,
                            newTimezone,
                            now
                    )
            );
        }

        if (settingsNeedsUpdate) {
            UpdateUserSettingsCommand settingsCmd = command.settings().value();

            int newWeekStartsOn = settingsCmd.weekStartsOn().isSpecified()
                    ? settingsCmd.weekStartsOn().value()
                    : current.settings().weekStartsOn();

            String newMeasurementSystem = settingsCmd.measurementSystem().isSpecified()
                    ? settingsCmd.measurementSystem().value()
                    : current.settings().measurementSystem();

            Map<String, Object> newAccessibility = settingsCmd.accessibilityPreferences().isSpecified()
                    ? settingsCmd.accessibilityPreferences().value()
                    : current.settings().accessibilityPreferences();

            Map<String, Object> newPrivacy = settingsCmd.privacyPreferences().isSpecified()
                    ? settingsCmd.privacyPreferences().value()
                    : current.settings().privacyPreferences();

            userUpdatePort.upsertUserSettings(
                    userId,
                    new UserSettingsData(
                            newWeekStartsOn,
                            newMeasurementSystem,
                            newAccessibility,
                            newPrivacy,
                            now
                    )
            );
        }

        return currentUserQuery.findCurrentUserById(userId)
                .orElseThrow(() -> new UserNotFoundException("Authenticated user not found after update: " + userId));
    }
}
