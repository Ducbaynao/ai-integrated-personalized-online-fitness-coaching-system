package com.fitnesscoaching.platform.modules.auth.application.port.out;

import com.fitnesscoaching.platform.modules.auth.domain.OneTimeTokenRecord;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenPort {

    OneTimeTokenRecord saveAndFlush(OneTimeTokenRecord token);

    Optional<OneTimeTokenRecord> findByTokenHashAndPurpose(String tokenHash, String purpose);

    int consumeToken(UUID id, Instant currentTime);
}
