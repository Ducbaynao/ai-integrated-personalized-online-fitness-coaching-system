package com.fitnesscoaching.platform.modules.student.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fitnesscoaching.platform.modules.student.application.model.PatchField;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;

public class UpdateStudentProfileRequest {

    @PastOrPresent(message = "Date of birth cannot be in the future")
    private LocalDate dateOfBirth;

    private Gender gender;

    private TrainingExperienceLevel trainingExperienceLevel;

    @DecimalMin(value = "0.0", message = "Training experience months cannot be negative")
    private BigDecimal trainingExperienceMonths;

    @Min(value = 1, message = "Available days per week must be between 1 and 7")
    @Max(value = 7, message = "Available days per week must be between 1 and 7")
    private Integer availableDaysPerWeek;

    @Min(value = 5, message = "Preferred session minutes must be between 5 and 480")
    @Max(value = 480, message = "Preferred session minutes must be between 5 and 480")
    private Integer preferredSessionMinutes;

    private Boolean onboardingCompleted;

    private boolean dateOfBirthSpecified;
    private boolean genderSpecified;
    private boolean trainingExperienceLevelSpecified;
    private boolean trainingExperienceMonthsSpecified;
    private boolean availableDaysPerWeekSpecified;
    private boolean preferredSessionMinutesSpecified;
    private boolean onboardingCompletedSpecified;

    public UpdateStudentProfileRequest() {}

    @JsonProperty("dateOfBirth")
    @JsonSetter(nulls = Nulls.SET)
    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
        this.dateOfBirthSpecified = true;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    @JsonIgnore
    public boolean isDateOfBirthSpecified() {
        return dateOfBirthSpecified;
    }

    @JsonProperty("gender")
    @JsonSetter(nulls = Nulls.SET)
    public void setGender(Gender gender) {
        this.gender = gender;
        this.genderSpecified = true;
    }

    public Gender getGender() {
        return gender;
    }

    @JsonIgnore
    public boolean isGenderSpecified() {
        return genderSpecified;
    }

    @JsonProperty("trainingExperienceLevel")
    @JsonSetter(nulls = Nulls.SET)
    public void setTrainingExperienceLevel(TrainingExperienceLevel trainingExperienceLevel) {
        this.trainingExperienceLevel = trainingExperienceLevel;
        this.trainingExperienceLevelSpecified = true;
    }

    public TrainingExperienceLevel getTrainingExperienceLevel() {
        return trainingExperienceLevel;
    }

    @JsonIgnore
    public boolean isTrainingExperienceLevelSpecified() {
        return trainingExperienceLevelSpecified;
    }

    @JsonProperty("trainingExperienceMonths")
    @JsonSetter(nulls = Nulls.SET)
    public void setTrainingExperienceMonths(BigDecimal trainingExperienceMonths) {
        this.trainingExperienceMonths = trainingExperienceMonths;
        this.trainingExperienceMonthsSpecified = true;
    }

    public BigDecimal getTrainingExperienceMonths() {
        return trainingExperienceMonths;
    }

    @JsonIgnore
    public boolean isTrainingExperienceMonthsSpecified() {
        return trainingExperienceMonthsSpecified;
    }

    @JsonProperty("availableDaysPerWeek")
    @JsonSetter(nulls = Nulls.SET)
    public void setAvailableDaysPerWeek(Integer availableDaysPerWeek) {
        this.availableDaysPerWeek = availableDaysPerWeek;
        this.availableDaysPerWeekSpecified = true;
    }

    public Integer getAvailableDaysPerWeek() {
        return availableDaysPerWeek;
    }

    @JsonIgnore
    public boolean isAvailableDaysPerWeekSpecified() {
        return availableDaysPerWeekSpecified;
    }

    @JsonProperty("preferredSessionMinutes")
    @JsonSetter(nulls = Nulls.SET)
    public void setPreferredSessionMinutes(Integer preferredSessionMinutes) {
        this.preferredSessionMinutes = preferredSessionMinutes;
        this.preferredSessionMinutesSpecified = true;
    }

    public Integer getPreferredSessionMinutes() {
        return preferredSessionMinutes;
    }

    @JsonIgnore
    public boolean isPreferredSessionMinutesSpecified() {
        return preferredSessionMinutesSpecified;
    }

    @JsonProperty("onboardingCompleted")
    @JsonSetter(nulls = Nulls.SET)
    public void setOnboardingCompleted(Boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
        this.onboardingCompletedSpecified = true;
    }

    public Boolean getOnboardingCompleted() {
        return onboardingCompleted;
    }

    @JsonIgnore
    public boolean isOnboardingCompletedSpecified() {
        return onboardingCompletedSpecified;
    }

    @AssertTrue(message = "At least one property must be provided")
    public boolean isAtLeastOnePropertyProvided() {
        return dateOfBirthSpecified
                || genderSpecified
                || trainingExperienceLevelSpecified
                || trainingExperienceMonthsSpecified
                || availableDaysPerWeekSpecified
                || preferredSessionMinutesSpecified
                || onboardingCompletedSpecified;
    }

    @AssertTrue(message = "onboardingCompleted cannot be null")
    public boolean isOnboardingCompletedValid() {
        return !onboardingCompletedSpecified || onboardingCompleted != null;
    }

    public PatchField<LocalDate> toDateOfBirthPatch() {
        return dateOfBirthSpecified ? PatchField.of(dateOfBirth) : PatchField.omitted();
    }

    public PatchField<Gender> toGenderPatch() {
        return genderSpecified ? PatchField.of(gender) : PatchField.omitted();
    }

    public PatchField<TrainingExperienceLevel> toTrainingExperienceLevelPatch() {
        return trainingExperienceLevelSpecified ? PatchField.of(trainingExperienceLevel) : PatchField.omitted();
    }

    public PatchField<BigDecimal> toTrainingExperienceMonthsPatch() {
        return trainingExperienceMonthsSpecified ? PatchField.of(trainingExperienceMonths) : PatchField.omitted();
    }

    public PatchField<Integer> toAvailableDaysPerWeekPatch() {
        return availableDaysPerWeekSpecified ? PatchField.of(availableDaysPerWeek) : PatchField.omitted();
    }

    public PatchField<Integer> toPreferredSessionMinutesPatch() {
        return preferredSessionMinutesSpecified ? PatchField.of(preferredSessionMinutes) : PatchField.omitted();
    }

    public PatchField<Boolean> toOnboardingCompletedPatch() {
        return onboardingCompletedSpecified ? PatchField.of(onboardingCompleted) : PatchField.omitted();
    }
}
