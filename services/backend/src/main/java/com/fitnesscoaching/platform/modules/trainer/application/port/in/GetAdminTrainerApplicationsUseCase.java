package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;

public interface GetAdminTrainerApplicationsUseCase {

    AdminTrainerApplicationPage getApplications(GetAdminTrainerApplicationsQuery query);
}
