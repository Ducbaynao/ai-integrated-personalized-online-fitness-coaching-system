package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.HistoricalAuthorityRequest;

public interface CoachingAuthorityQuery {
    AuthorityDecision evaluateCurrent(AuthorityRequest request);

    AuthorityDecision evaluateHistorical(HistoricalAuthorityRequest request);
}
