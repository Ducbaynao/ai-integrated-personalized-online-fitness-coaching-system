package com.fitnesscoaching.platform.modules.support.application.port.in;

import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AddSupportCaseNoteRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AssignSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.CreateSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.UpdateSupportCaseStatusRequest;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseDetail;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCasePage;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;

import java.util.UUID;

public interface SupportCaseUseCase {

    SupportCasePage searchSupportCases(UUID adminUserId, String search, String status, String priority, int page, int size);

    SupportCaseDetail getSupportCaseDetail(UUID adminUserId, UUID caseId);

    SupportCaseSummary createSupportCase(UUID adminUserId, CreateSupportCaseRequest request);

    SupportCaseDetail updateStatus(UUID adminUserId, UUID caseId, UpdateSupportCaseStatusRequest request);

    SupportCaseDetail assignCase(UUID adminUserId, UUID caseId, AssignSupportCaseRequest request);

    SupportCaseDetail addNote(UUID adminUserId, UUID caseId, AddSupportCaseNoteRequest request);
}
