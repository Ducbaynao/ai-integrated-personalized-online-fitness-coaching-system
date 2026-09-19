package com.fitnesscoaching.platform.modules.auth.application.port.in;

public interface RefreshSessionUseCase {

    TokenPairResult refresh(RefreshSessionCommand command);
}
