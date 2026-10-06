package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryPage;

public interface TrainerDirectoryQuery {
    TrainerDirectoryPage searchDiscoverableTrainers(String displayNameQuery, int page, int size);
}
