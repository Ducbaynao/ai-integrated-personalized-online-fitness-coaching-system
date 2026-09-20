package com.fitnesscoaching.platform.modules.user.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public class UpdateCurrentUserRequest {

    @Size(min = 1, max = 160, message = "displayName length must be between 1 and 160 characters")
    private String displayName;

    @Size(max = 32, message = "phoneNumber length must be at most 32 characters")
    private String phoneNumber;

    @Size(max = 16, message = "preferredLocale length must be at most 16 characters")
    private String preferredLocale;

    @Size(max = 64, message = "timezone length must be at most 64 characters")
    private String timezone;

    @Valid
    private UpdateUserSettingsRequest settings;

    private boolean displayNameSpecified;
    private boolean phoneNumberSpecified;
    private boolean preferredLocaleSpecified;
    private boolean timezoneSpecified;
    private boolean settingsSpecified;

    @JsonCreator
    public UpdateCurrentUserRequest() {}

    public static UpdateCurrentUserRequest of(
            String displayName,
            boolean phoneNumberSpecified,
            String phoneNumber,
            String preferredLocale,
            String timezone,
            UpdateUserSettingsRequest settings
    ) {
        UpdateCurrentUserRequest req = new UpdateCurrentUserRequest();
        if (displayName != null) req.setDisplayName(displayName);
        if (phoneNumberSpecified) {
            req.setPhoneNumber(phoneNumber);
        }
        if (preferredLocale != null) req.setPreferredLocale(preferredLocale);
        if (timezone != null) req.setTimezone(timezone);
        if (settings != null) req.setSettings(settings);
        return req;
    }

    @JsonProperty("displayName")
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNameSpecified = true;
    }

    public String getDisplayName() {
        return displayName;
    }

    @JsonIgnore
    public boolean isDisplayNameSpecified() {
        return displayNameSpecified;
    }

    @JsonProperty("phoneNumber")
    @JsonSetter(nulls = Nulls.SET)
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        this.phoneNumberSpecified = true;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    @JsonIgnore
    public boolean isPhoneNumberSpecified() {
        return phoneNumberSpecified;
    }

    @JsonProperty("preferredLocale")
    public void setPreferredLocale(String preferredLocale) {
        this.preferredLocale = preferredLocale;
        this.preferredLocaleSpecified = true;
    }

    public String getPreferredLocale() {
        return preferredLocale;
    }

    @JsonIgnore
    public boolean isPreferredLocaleSpecified() {
        return preferredLocaleSpecified;
    }

    @JsonProperty("timezone")
    public void setTimezone(String timezone) {
        this.timezone = timezone;
        this.timezoneSpecified = true;
    }

    public String getTimezone() {
        return timezone;
    }

    @JsonIgnore
    public boolean isTimezoneSpecified() {
        return timezoneSpecified;
    }

    @JsonProperty("settings")
    @JsonSetter(nulls = Nulls.SET)
    public void setSettings(UpdateUserSettingsRequest settings) {
        this.settings = settings;
        this.settingsSpecified = true;
    }

    public UpdateUserSettingsRequest getSettings() {
        return settings;
    }

    @JsonIgnore
    public boolean isSettingsSpecified() {
        return settingsSpecified;
    }

    @AssertTrue(message = "displayName cannot be null when provided")
    @JsonIgnore
    public boolean isDisplayNameValid() {
        return !displayNameSpecified || displayName != null;
    }

    @AssertTrue(message = "preferredLocale cannot be null when provided")
    @JsonIgnore
    public boolean isPreferredLocaleValid() {
        return !preferredLocaleSpecified || preferredLocale != null;
    }

    @AssertTrue(message = "timezone cannot be null when provided")
    @JsonIgnore
    public boolean isTimezoneValid() {
        return !timezoneSpecified || timezone != null;
    }

    @AssertTrue(message = "settings cannot be null when provided")
    @JsonIgnore
    public boolean isSettingsValid() {
        return !settingsSpecified || settings != null;
    }

    @AssertTrue(message = "At least one property must be provided")
    @JsonIgnore
    public boolean isAtLeastOnePropertyProvided() {
        return displayNameSpecified
                || phoneNumberSpecified
                || preferredLocaleSpecified
                || timezoneSpecified
                || (settingsSpecified && settings != null);
    }

    @Override
    public String toString() {
        return "UpdateCurrentUserRequest[" +
                "displayName=" + (displayNameSpecified ? displayName : "omitted") +
                ", phoneNumber=" + (phoneNumberSpecified ? (phoneNumber != null ? "[REDACTED]" : "null") : "omitted") +
                ", preferredLocale=" + (preferredLocaleSpecified ? preferredLocale : "omitted") +
                ", timezone=" + (timezoneSpecified ? timezone : "omitted") +
                ", settings=" + settings +
                "]";
    }
}
