package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;

import java.util.UUID;

public interface AiWorkoutAdjustmentUseCase {

    WorkoutAdjustmentApiResponse generateAdjustment(UUID actorId, WorkoutAdjustmentApiRequest request);

    AiRecommendation getAdjustment(UUID actorId, UUID recommendationId);

    AcceptAdjustmentApiResponse acceptAdjustment(UUID actorId, UUID recommendationId, AcceptAdjustmentApiRequest request);

    RejectAdjustmentApiResponse rejectAdjustment(UUID actorId, UUID recommendationId, RejectAdjustmentApiRequest request);
}
