package com.fitnesscoaching.platform.modules.student.application.port.in;

import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;

public interface UpdateStudentProfileUseCase {

    StudentProfile updateStudentProfile(UpdateStudentProfileCommand command);
}
