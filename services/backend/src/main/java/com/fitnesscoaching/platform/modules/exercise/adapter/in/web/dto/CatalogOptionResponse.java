package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.CatalogOption;

public record CatalogOptionResponse(String code, String name) {

    public static CatalogOptionResponse fromDomain(CatalogOption option) {
        return option == null ? null : new CatalogOptionResponse(option.code(), option.name());
    }
}
