package com.fitnesscoaching.platform.modules.administration.privileged.application.port.in;

import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.PrivilegedAccessPageResponse;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.PrivilegedAccessResponse;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RequestPrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RevokePrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessPage;

import java.util.List;
import java.util.UUID;

public interface PrivilegedAccessUseCase {

    PrivilegedAccessPage searchRequests(UUID adminUserId, String status, String purpose, int page, int size);

    PrivilegedAccessModel getRequest(UUID adminUserId, UUID requestId);

    List<PrivilegedAccessModel> getActiveRequestsForAdmin(UUID adminUserId);

    PrivilegedAccessModel requestPrivilegedAccess(UUID adminUserId, RequestPrivilegedAccessDto request);

    PrivilegedAccessModel revokePrivilegedAccess(UUID adminUserId, UUID requestId, RevokePrivilegedAccessDto request);
}
