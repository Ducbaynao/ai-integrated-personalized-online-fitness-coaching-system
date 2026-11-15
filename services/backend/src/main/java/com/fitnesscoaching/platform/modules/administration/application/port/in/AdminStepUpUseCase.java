package com.fitnesscoaching.platform.modules.administration.application.port.in;

import com.fitnesscoaching.platform.modules.administration.application.model.StepUpResult;

import java.util.UUID;

public interface AdminStepUpUseCase {

    StepUpResult verifyAndIssueStepUp(UUID adminUserId, String actionCode, String password);

    UUID resolveOrVerifyStepUp(UUID adminUserId, String actionCode, String password, UUID providedStepUpId);
}
