package com.fitnesscoaching.platform.modules.auth.application.port.in;

public interface ConfirmEmailUseCase {

    void confirmEmail(ConfirmEmailCommand command);
}
