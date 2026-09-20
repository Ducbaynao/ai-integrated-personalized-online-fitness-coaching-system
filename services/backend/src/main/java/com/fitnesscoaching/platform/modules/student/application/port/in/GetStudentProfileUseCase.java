package com.fitnesscoaching.platform.modules.student.application.port.in;

import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;

import java.util.UUID;

public interface GetStudentProfileUseCase {

    StudentProfile getStudentProfile(UUID userId);
}
