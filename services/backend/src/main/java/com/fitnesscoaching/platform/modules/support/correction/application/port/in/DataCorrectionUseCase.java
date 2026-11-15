package com.fitnesscoaching.platform.modules.support.correction.application.port.in;

import com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto.ExecuteDataCorrectionRequest;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionModel;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionPage;

import java.util.UUID;

public interface DataCorrectionUseCase {

    DataCorrectionPage searchCorrections(UUID adminUserId, UUID supportCaseId, String targetType, int page, int size);

    DataCorrectionModel getCorrection(UUID adminUserId, UUID correctionId);

    DataCorrectionModel executeCorrection(UUID adminUserId, ExecuteDataCorrectionRequest request);
}
