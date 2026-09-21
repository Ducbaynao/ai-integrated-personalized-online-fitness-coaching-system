package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public class CreateTrainerProfileRequest {

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

    private Boolean acceptingStudents = false;

    public CreateTrainerProfileRequest() {}

    public CreateTrainerProfileRequest(String publicSlug, String bio, BigDecimal yearsExperience, Boolean acceptingStudents) {
        this.publicSlug = publicSlug;
        this.bio = bio;
        this.yearsExperience = yearsExperience;
        this.acceptingStudents = acceptingStudents != null ? acceptingStudents : false;
    }

    public String getPublicSlug() {
        return publicSlug;
    }

    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public BigDecimal getYearsExperience() {
        return yearsExperience;
    }

    public void setYearsExperience(BigDecimal yearsExperience) {
        this.yearsExperience = yearsExperience;
    }

    public Boolean getAcceptingStudents() {
        return acceptingStudents;
    }

    public void setAcceptingStudents(Boolean acceptingStudents) {
        this.acceptingStudents = acceptingStudents != null ? acceptingStudents : false;
    }

    public CreateTrainerProfileCommand toCommand(UUID userId) {
        return new CreateTrainerProfileCommand(
                userId,
                publicSlug,
                bio,
                yearsExperience,
                acceptingStudents != null ? acceptingStudents : false
        );
    }
}
