package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;

import java.util.UUID;

public interface AiWorkoutRescheduleUseCase {

    WorkoutRescheduleApiResponse generateReschedule(UUID actorId, WorkoutRescheduleApiRequest request);

    AiRecommendation getReschedule(UUID actorId, UUID recommendationId);

    AcceptRescheduleApiResponse acceptReschedule(UUID actorId, UUID recommendationId, AcceptRescheduleApiRequest request);

    RejectRescheduleApiResponse rejectReschedule(UUID actorId, UUID recommendationId, RejectRescheduleApiRequest request);
}
