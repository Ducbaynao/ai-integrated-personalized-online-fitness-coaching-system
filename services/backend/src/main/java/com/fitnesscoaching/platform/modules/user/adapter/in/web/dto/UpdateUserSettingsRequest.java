package com.fitnesscoaching.platform.modules.user.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public class UpdateUserSettingsRequest {

    @Min(value = 0, message = "weekStartsOn must be at least 0")
    @Max(value = 6, message = "weekStartsOn must be at most 6")
    private Integer weekStartsOn;

    @Pattern(regexp = "^(METRIC|IMPERIAL)$", message = "measurementSystem must be METRIC or IMPERIAL")
    private String measurementSystem;

    private Map<String, Object> accessibilityPreferences;
    private Map<String, Object> privacyPreferences;

    private boolean weekStartsOnSpecified;
    private boolean measurementSystemSpecified;
    private boolean accessibilityPreferencesSpecified;
    private boolean privacyPreferencesSpecified;

    @com.fasterxml.jackson.annotation.JsonCreator
    public UpdateUserSettingsRequest() {}

    public static UpdateUserSettingsRequest of(
            Integer weekStartsOn,
            String measurementSystem,
            Map<String, Object> accessibilityPreferences,
            Map<String, Object> privacyPreferences
    ) {
        UpdateUserSettingsRequest req = new UpdateUserSettingsRequest();
        if (weekStartsOn != null) req.setWeekStartsOn(weekStartsOn);
        if (measurementSystem != null) req.setMeasurementSystem(measurementSystem);
        if (accessibilityPreferences != null) req.setAccessibilityPreferences(accessibilityPreferences);
        if (privacyPreferences != null) req.setPrivacyPreferences(privacyPreferences);
        return req;
    }

    @JsonProperty("weekStartsOn")
    public void setWeekStartsOn(Integer weekStartsOn) {
        this.weekStartsOn = weekStartsOn;
        this.weekStartsOnSpecified = true;
    }

    public Integer getWeekStartsOn() {
        return weekStartsOn;
    }

    @JsonIgnore
    public boolean isWeekStartsOnSpecified() {
        return weekStartsOnSpecified;
    }

    @JsonProperty("measurementSystem")
    public void setMeasurementSystem(String measurementSystem) {
        this.measurementSystem = measurementSystem;
        this.measurementSystemSpecified = true;
    }

    public String getMeasurementSystem() {
        return measurementSystem;
    }

    @JsonIgnore
    public boolean isMeasurementSystemSpecified() {
        return measurementSystemSpecified;
    }

    @JsonProperty("accessibilityPreferences")
    public void setAccessibilityPreferences(Map<String, Object> accessibilityPreferences) {
        this.accessibilityPreferences = accessibilityPreferences;
        this.accessibilityPreferencesSpecified = true;
    }

    public Map<String, Object> getAccessibilityPreferences() {
        return accessibilityPreferences;
    }

    @JsonIgnore
    public boolean isAccessibilityPreferencesSpecified() {
        return accessibilityPreferencesSpecified;
    }

    @JsonProperty("privacyPreferences")
    public void setPrivacyPreferences(Map<String, Object> privacyPreferences) {
        this.privacyPreferences = privacyPreferences;
        this.privacyPreferencesSpecified = true;
    }

    public Map<String, Object> getPrivacyPreferences() {
        return privacyPreferences;
    }

    @JsonIgnore
    public boolean isPrivacyPreferencesSpecified() {
        return privacyPreferencesSpecified;
    }

    @AssertTrue(message = "weekStartsOn cannot be null when provided")
    @JsonIgnore
    public boolean isWeekStartsOnValid() {
        return !weekStartsOnSpecified || weekStartsOn != null;
    }

    @AssertTrue(message = "measurementSystem cannot be null when provided")
    @JsonIgnore
    public boolean isMeasurementSystemValid() {
        return !measurementSystemSpecified || measurementSystem != null;
    }

    @AssertTrue(message = "accessibilityPreferences cannot be null when provided")
    @JsonIgnore
    public boolean isAccessibilityPreferencesValid() {
        return !accessibilityPreferencesSpecified || accessibilityPreferences != null;
    }

    @AssertTrue(message = "privacyPreferences cannot be null when provided")
    @JsonIgnore
    public boolean isPrivacyPreferencesValid() {
        return !privacyPreferencesSpecified || privacyPreferences != null;
    }

    @JsonIgnore
    public boolean isAtLeastOnePropertyProvided() {
        return weekStartsOnSpecified
                || measurementSystemSpecified
                || accessibilityPreferencesSpecified
                || privacyPreferencesSpecified;
    }

    @Override
    public String toString() {
        return "UpdateUserSettingsRequest[" +
                "weekStartsOn=" + (weekStartsOnSpecified ? weekStartsOn : "omitted") +
                ", measurementSystem=" + (measurementSystemSpecified ? measurementSystem : "omitted") +
                ", accessibilityPreferences=" + (accessibilityPreferencesSpecified ? accessibilityPreferences : "omitted") +
                ", privacyPreferences=" + (privacyPreferencesSpecified ? (privacyPreferences != null ? "[REDACTED]" : "null") : "omitted") +
                "]";
    }
}
