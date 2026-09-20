package com.fitnesscoaching.platform.modules.auth.application.port.out;

import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.auth.domain.IssuedAccessToken;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;

import java.time.Instant;
import java.util.List;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(CurrentUserView user, Instant issuedAt);

    IssuedAccessToken issue(UserAccountRecord user, List<String> roles, Instant issuedAt);
}
