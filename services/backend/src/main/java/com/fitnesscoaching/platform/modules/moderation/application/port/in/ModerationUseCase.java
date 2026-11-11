package com.fitnesscoaching.platform.modules.moderation.application.port.in;

import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.CreateModerationCaseRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.PerformModerationActionRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.UpdateModerationStatusRequest;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationActionModel;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseDetail;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCasePage;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseSummary;

import java.util.UUID;

public interface ModerationUseCase {

    ModerationCasePage searchModerationCases(UUID adminUserId, String status, String priority, int page, int size);

    ModerationCaseDetail getModerationCaseDetail(UUID adminUserId, UUID caseId);

    ModerationCaseSummary createModerationCase(UUID adminUserId, CreateModerationCaseRequest request);

    ModerationCaseDetail updateStatus(UUID adminUserId, UUID caseId, UpdateModerationStatusRequest request);

    ModerationActionModel performAction(UUID adminUserId, UUID caseId, PerformModerationActionRequest request);
}
