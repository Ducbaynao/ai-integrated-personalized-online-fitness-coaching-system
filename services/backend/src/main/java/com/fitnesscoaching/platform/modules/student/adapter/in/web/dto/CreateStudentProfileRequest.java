package com.fitnesscoaching.platform.modules.student.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class CreateStudentProfileRequest {

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

    private Boolean onboardingCompleted = false;
    private boolean onboardingCompletedSpecified = false;

    public CreateStudentProfileRequest() {}

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public TrainingExperienceLevel getTrainingExperienceLevel() {
        return trainingExperienceLevel;
    }

    public void setTrainingExperienceLevel(TrainingExperienceLevel trainingExperienceLevel) {
        this.trainingExperienceLevel = trainingExperienceLevel;
    }

    public BigDecimal getTrainingExperienceMonths() {
        return trainingExperienceMonths;
    }

    public void setTrainingExperienceMonths(BigDecimal trainingExperienceMonths) {
        this.trainingExperienceMonths = trainingExperienceMonths;
    }

    public Integer getAvailableDaysPerWeek() {
        return availableDaysPerWeek;
    }

    public void setAvailableDaysPerWeek(Integer availableDaysPerWeek) {
        this.availableDaysPerWeek = availableDaysPerWeek;
    }

    public Integer getPreferredSessionMinutes() {
        return preferredSessionMinutes;
    }

    public void setPreferredSessionMinutes(Integer preferredSessionMinutes) {
        this.preferredSessionMinutes = preferredSessionMinutes;
    }

    public Boolean getOnboardingCompleted() {
        return onboardingCompleted;
    }

    @JsonProperty("onboardingCompleted")
    @JsonSetter(nulls = Nulls.SET)
    public void setOnboardingCompleted(Boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
        this.onboardingCompletedSpecified = true;
    }

    @JsonIgnore
    public boolean isOnboardingCompletedSpecified() {
        return onboardingCompletedSpecified;
    }

    @AssertTrue(message = "onboardingCompleted cannot be null")
    public boolean isOnboardingCompletedValid() {
        return !onboardingCompletedSpecified || onboardingCompleted != null;
    }

    public CreateStudentProfileCommand toCommand(UUID userId) {
        return new CreateStudentProfileCommand(
                userId,
                dateOfBirth,
                gender,
                trainingExperienceLevel,
                trainingExperienceMonths,
                availableDaysPerWeek,
                preferredSessionMinutes,
                onboardingCompleted
        );
    }
}
