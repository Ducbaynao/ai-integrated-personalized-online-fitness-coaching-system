package com.fitnesscoaching.platform.modules.trainer.application.service;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryItem;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerDirectoryQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummaryPage;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainerDirectoryService implements TrainerDirectoryQuery {
    private final TrainerProfilePort profiles;
    private final UserDirectoryQuery users;

    public TrainerDirectoryService(TrainerProfilePort profiles, UserDirectoryQuery users) {
        this.profiles = profiles;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public TrainerDirectoryPage searchDiscoverableTrainers(String displayNameQuery, int page, int size) {
        UserDisplaySummaryPage result = users.searchActiveRoleMembers(
                profiles.findDiscoverableTrainerIds(), "TRAINER", displayNameQuery, page, size);
        return new TrainerDirectoryPage(result.items().stream()
                .map(user -> new TrainerDirectoryItem(user.userId(), user.displayName()))
                .toList(), result.page(), result.size());
    }
}
