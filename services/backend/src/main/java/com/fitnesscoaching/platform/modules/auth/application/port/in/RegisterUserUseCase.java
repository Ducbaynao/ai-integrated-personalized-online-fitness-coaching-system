package com.fitnesscoaching.platform.modules.auth.application.port.in;

public interface RegisterUserUseCase {

    RegisterUserResult register(RegisterUserCommand command);
}
