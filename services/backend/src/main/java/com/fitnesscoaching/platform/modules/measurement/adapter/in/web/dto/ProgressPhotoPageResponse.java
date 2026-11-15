package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import java.util.List;

public record ProgressPhotoPageResponse(
        List<ProgressPhotoResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasMore
) {}
