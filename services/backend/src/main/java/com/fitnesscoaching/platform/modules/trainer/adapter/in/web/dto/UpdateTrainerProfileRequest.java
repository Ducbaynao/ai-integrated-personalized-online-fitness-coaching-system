package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fitnesscoaching.platform.modules.trainer.application.model.PatchField;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class UpdateTrainerProfileRequest {

    @Pattern(
            regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
            message = "Public slug must contain only lowercase alphanumeric characters and single hyphens"
    )
    @Size(max = 100, message = "Public slug cannot exceed 100 characters")
    private String publicSlug;

    @Size(max = 4000, message = "Bio cannot exceed 4000 characters")
    private String bio;

    @DecimalMin(value = "0.0", message = "Years of experience cannot be negative")
    @DecimalMax(value = "99.99", message = "Years of experience cannot exceed 99.99")
    @Digits(integer = 2, fraction = 2, message = "Years of experience must have at most 2 integer digits and 2 decimal places")
    private BigDecimal yearsExperience;

    private Boolean acceptingStudents;

    private boolean publicSlugSpecified;
    private boolean bioSpecified;
    private boolean yearsExperienceSpecified;
    private boolean acceptingStudentsSpecified;

    public UpdateTrainerProfileRequest() {}

    @JsonProperty("publicSlug")
    @JsonSetter(nulls = Nulls.SET)
    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
        this.publicSlugSpecified = true;
    }

    public String getPublicSlug() {
        return publicSlug;
    }

    @JsonIgnore
    public boolean isPublicSlugSpecified() {
        return publicSlugSpecified;
    }

    @JsonProperty("bio")
    @JsonSetter(nulls = Nulls.SET)
    public void setBio(String bio) {
        this.bio = bio;
        this.bioSpecified = true;
    }

    public String getBio() {
        return bio;
    }

    @JsonIgnore
    public boolean isBioSpecified() {
        return bioSpecified;
    }

    @JsonProperty("yearsExperience")
    @JsonSetter(nulls = Nulls.SET)
    public void setYearsExperience(BigDecimal yearsExperience) {
        this.yearsExperience = yearsExperience;
        this.yearsExperienceSpecified = true;
    }

    public BigDecimal getYearsExperience() {
        return yearsExperience;
    }

    @JsonIgnore
    public boolean isYearsExperienceSpecified() {
        return yearsExperienceSpecified;
    }

    @JsonProperty("acceptingStudents")
    @JsonSetter(nulls = Nulls.SET)
    public void setAcceptingStudents(Boolean acceptingStudents) {
        this.acceptingStudents = acceptingStudents;
        this.acceptingStudentsSpecified = true;
    }

    public Boolean getAcceptingStudents() {
        return acceptingStudents;
    }

    @JsonIgnore
    public boolean isAcceptingStudentsSpecified() {
        return acceptingStudentsSpecified;
    }

    @AssertTrue(message = "At least one property must be provided")
    public boolean isAtLeastOnePropertyProvided() {
        return publicSlugSpecified
                || bioSpecified
                || yearsExperienceSpecified
                || acceptingStudentsSpecified;
    }

    @AssertTrue(message = "acceptingStudents cannot be null")
    public boolean isAcceptingStudentsValid() {
        return !acceptingStudentsSpecified || acceptingStudents != null;
    }

    public PatchField<String> toPublicSlugPatch() {
        return publicSlugSpecified ? PatchField.of(publicSlug) : PatchField.omitted();
    }

    public PatchField<String> toBioPatch() {
        return bioSpecified ? PatchField.of(bio) : PatchField.omitted();
    }

    public PatchField<BigDecimal> toYearsExperiencePatch() {
        return yearsExperienceSpecified ? PatchField.of(yearsExperience) : PatchField.omitted();
    }

    public PatchField<Boolean> toAcceptingStudentsPatch() {
        return acceptingStudentsSpecified ? PatchField.of(acceptingStudents) : PatchField.omitted();
    }
}
