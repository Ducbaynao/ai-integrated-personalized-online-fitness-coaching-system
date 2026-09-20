package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;

public interface UpdateCurrentUserUseCase {

    CurrentUserView updateCurrentUser(UpdateCurrentUserCommand command);
}
