package com.fitnesscoaching.platform.modules.user.application.model;

import java.util.Objects;

public final class PatchField<T> {

    private final boolean specified;
    private final T value;

    private PatchField(boolean specified, T value) {
        this.specified = specified;
        this.value = value;
    }

    public static <T> PatchField<T> omitted() {
        return new PatchField<>(false, null);
    }

    public static <T> PatchField<T> of(T value) {
        return new PatchField<>(true, value);
    }

    public static <T> PatchField<T> ofNull() {
        return new PatchField<>(true, null);
    }

    public boolean isSpecified() {
        return specified;
    }

    public boolean isNull() {
        return specified && value == null;
    }

    public boolean isPresent() {
        return specified && value != null;
    }

    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatchField<?> that)) return false;
        return specified == that.specified && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(specified, value);
    }

    @Override
    public String toString() {
        if (!specified) {
            return "omitted";
        }
        return String.valueOf(value);
    }
}
