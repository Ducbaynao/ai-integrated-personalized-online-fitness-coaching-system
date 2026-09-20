package com.fitnesscoaching.platform.modules.student.application.port.in;

import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;

public interface CreateStudentProfileUseCase {

    StudentProfile createStudentProfile(CreateStudentProfileCommand command);
}
