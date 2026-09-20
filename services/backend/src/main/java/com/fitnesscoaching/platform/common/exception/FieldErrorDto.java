package com.fitnesscoaching.platform.common.exception;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FieldErrorDto(
        @JsonProperty("field") String field,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message
) {
}
