package com.fitnesscoaching.platform.modules.auth.application.port.in;

public interface LoginUseCase {

    TokenPairResult login(LoginCommand command);
}
