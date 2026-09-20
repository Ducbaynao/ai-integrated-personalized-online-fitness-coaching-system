package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.PatchField;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserProfileUpdateData;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsData;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateUserSettingsCommand;
import com.fitnesscoaching.platform.modules.user.application.port.out.UserUpdatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceUnitTest {

    private CurrentUserQuery currentUserQuery;
    private UserUpdatePort userUpdatePort;
    private Clock clock;
    private UserService userService;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T10:00:00Z");

    @BeforeEach
    void setUp() {
        currentUserQuery = mock(CurrentUserQuery.class);
        userUpdatePort = mock(UserUpdatePort.class);
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        userService = new UserService(currentUserQuery, userUpdatePort, clock);
    }

    private CurrentUserView createSampleUser(UUID userId, String phoneNumber, UserSettingsView settings) {
        return new CurrentUserView(
                userId,
                "user@example.com",
                "Alice Athlete",
                AccountStatus.ACTIVE,
                "vi-VN",
                "Asia/Ho_Chi_Minh",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                phoneNumber,
                List.of("STUDENT"),
                new UserCapabilitiesView(true, false, false),
                settings != null ? settings : UserSettingsView.defaults()
        );
    }

    @Test
    @DisplayName("GET succeeds when user exists")
    void getCurrentUser_success() {
        UUID userId = UUID.randomUUID();
        CurrentUserView existing = createSampleUser(userId, "+84901234567", null);
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));

        CurrentUserView result = userService.getCurrentUser(userId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.displayName()).isEqualTo("Alice Athlete");
        assertThat(result.phoneNumber()).isEqualTo("+84901234567");
        assertThat(result.roles()).containsExactly("STUDENT");
        assertThat(result.capabilities().canCoach()).isFalse();
    }

    @Test
    @DisplayName("GET throws UserNotFoundException when user does not exist")
    void getCurrentUser_notFound() {
        UUID userId = UUID.randomUUID();
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser(userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    @Test
    @DisplayName("PATCH modifies only provided fields and leaves omitted profile fields untouched")
    void updateCurrentUser_onlyProvidedFieldsUpdated() {
        UUID userId = UUID.randomUUID();
        CurrentUserView existing = createSampleUser(userId, "+84901234567", null);
        CurrentUserView updated = createSampleUser(userId, "+84901234567", null);
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing), Optional.of(updated));

        UpdateCurrentUserCommand command = new UpdateCurrentUserCommand(
                userId,
                PatchField.of("Updated Alice"),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        userService.updateCurrentUser(command);

        ArgumentCaptor<UserProfileUpdateData> captor = ArgumentCaptor.forClass(UserProfileUpdateData.class);
        verify(userUpdatePort).updateUserProfile(eq(userId), captor.capture());
        UserProfileUpdateData data = captor.getValue();

        assertThat(data.displayName()).isEqualTo("Updated Alice");
        assertThat(data.phoneNumberSpecified()).isFalse();
        assertThat(data.phoneNumber()).isNull();
        assertThat(data.preferredLocale()).isNull();
        assertThat(data.timezone()).isNull();
        assertThat(data.updatedAt()).isEqualTo(FIXED_NOW);

        verify(userUpdatePort, never()).upsertUserSettings(any(), any());
    }

    @Test
    @DisplayName("PATCH differentiates between omitted phoneNumber and explicit null phoneNumber")
    void updateCurrentUser_omittedVsExplicitNullPhoneNumber() {
        UUID userId = UUID.randomUUID();
        CurrentUserView existing = createSampleUser(userId, "+84901234567", null);
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));

        // 1. Explicit null -> clears phone number
        UpdateCurrentUserCommand explicitNullCmd = new UpdateCurrentUserCommand(
                userId,
                PatchField.omitted(),
                PatchField.ofNull(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );
        userService.updateCurrentUser(explicitNullCmd);

        ArgumentCaptor<UserProfileUpdateData> captor = ArgumentCaptor.forClass(UserProfileUpdateData.class);
        verify(userUpdatePort).updateUserProfile(eq(userId), captor.capture());
        UserProfileUpdateData nullPhoneData = captor.getValue();
        assertThat(nullPhoneData.phoneNumberSpecified()).isTrue();
        assertThat(nullPhoneData.phoneNumber()).isNull();

        // 2. Omitted phone number -> does not update phone number
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));
        UpdateCurrentUserCommand omittedPhoneCmd = new UpdateCurrentUserCommand(
                userId,
                PatchField.of("New Name"),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );
        userService.updateCurrentUser(omittedPhoneCmd);

        ArgumentCaptor<UserProfileUpdateData> captor2 = ArgumentCaptor.forClass(UserProfileUpdateData.class);
        verify(userUpdatePort, org.mockito.Mockito.times(2)).updateUserProfile(eq(userId), captor2.capture());
        UserProfileUpdateData omittedPhoneData = captor2.getAllValues().get(1);
        assertThat(omittedPhoneData.phoneNumberSpecified()).isFalse();
    }

    @Test
    @DisplayName("PATCH nested settings update preserves omitted settings fields")
    void updateCurrentUser_nestedSettingsUpdatePreservesOmittedFields() {
        UUID userId = UUID.randomUUID();
        UserSettingsView existingSettings = new UserSettingsView(
                1,
                "IMPERIAL",
                Map.of("highContrast", true),
                Map.of("shareProfile", false)
        );
        CurrentUserView existing = createSampleUser(userId, "+84901234567", existingSettings);
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));

        // Update ONLY weekStartsOn to 0
        UpdateCurrentUserCommand command = new UpdateCurrentUserCommand(
                userId,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(new UpdateUserSettingsCommand(
                        PatchField.of(0),
                        PatchField.omitted(),
                        PatchField.omitted(),
                        PatchField.omitted()
                ))
        );

        userService.updateCurrentUser(command);

        ArgumentCaptor<UserSettingsData> captor = ArgumentCaptor.forClass(UserSettingsData.class);
        verify(userUpdatePort).upsertUserSettings(eq(userId), captor.capture());
        UserSettingsData data = captor.getValue();

        assertThat(data.weekStartsOn()).isEqualTo(0);
        assertThat(data.measurementSystem()).isEqualTo("IMPERIAL"); // preserved!
        assertThat(data.accessibilityPreferences()).containsEntry("highContrast", true); // preserved!
        assertThat(data.privacyPreferences()).containsEntry("shareProfile", false); // preserved!
        assertThat(data.updatedAt()).isEqualTo(FIXED_NOW);

        verify(userUpdatePort, never()).updateUserProfile(any(), any());
    }

    @Test
    @DisplayName("PATCH upserts settings with defaults plus provided fields when settings row is missing")
    void updateCurrentUser_upsertSettingsWhenMissingRow() {
        UUID userId = UUID.randomUUID();
        // Missing settings returns defaults (weekStartsOn=1, METRIC, {}, {})
        CurrentUserView existing = createSampleUser(userId, "+84901234567", UserSettingsView.defaults());
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));

        // Provide only privacyPreferences
        UpdateCurrentUserCommand command = new UpdateCurrentUserCommand(
                userId,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(new UpdateUserSettingsCommand(
                        PatchField.omitted(),
                        PatchField.omitted(),
                        PatchField.omitted(),
                        PatchField.of(Map.of("allowTrainerDiscovery", false))
                ))
        );

        userService.updateCurrentUser(command);

        ArgumentCaptor<UserSettingsData> captor = ArgumentCaptor.forClass(UserSettingsData.class);
        verify(userUpdatePort).upsertUserSettings(eq(userId), captor.capture());
        UserSettingsData data = captor.getValue();

        assertThat(data.weekStartsOn()).isEqualTo(1); // default
        assertThat(data.measurementSystem()).isEqualTo("METRIC"); // default
        assertThat(data.accessibilityPreferences()).isEmpty(); // default
        assertThat(data.privacyPreferences()).containsEntry("allowTrainerDiscovery", false); // provided
        assertThat(data.updatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("PATCH throws UserNotFoundException when user does not exist")
    void updateCurrentUser_userNotFound() {
        UUID userId = UUID.randomUUID();
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.empty());

        UpdateCurrentUserCommand command = new UpdateCurrentUserCommand(
                userId,
                PatchField.of("Updated Name"),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted()
        );

        assertThatThrownBy(() -> userService.updateCurrentUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(userId.toString());

        verify(userUpdatePort, never()).updateUserProfile(any(), any());
        verify(userUpdatePort, never()).upsertUserSettings(any(), any());
    }

    @Test
    @DisplayName("PATCH with empty settings is a safe no-op returning current projection")
    void updateCurrentUser_emptySettingsIsSafeNoOp() {
        UUID userId = UUID.randomUUID();
        CurrentUserView existing = createSampleUser(userId, "+84901234567", null);
        when(currentUserQuery.findCurrentUserById(userId)).thenReturn(Optional.of(existing));

        UpdateCurrentUserCommand command = new UpdateCurrentUserCommand(
                userId,
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.omitted(),
                PatchField.of(new UpdateUserSettingsCommand(
                        PatchField.omitted(),
                        PatchField.omitted(),
                        PatchField.omitted(),
                        PatchField.omitted()
                ))
        );

        CurrentUserView result = userService.updateCurrentUser(command);

        assertThat(result).isSameAs(existing);
        verify(userUpdatePort, never()).updateUserProfile(any(), any());
        verify(userUpdatePort, never()).upsertUserSettings(any(), any());
    }
}
