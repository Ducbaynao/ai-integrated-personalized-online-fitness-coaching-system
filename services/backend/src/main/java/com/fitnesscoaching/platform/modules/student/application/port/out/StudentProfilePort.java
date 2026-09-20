package com.fitnesscoaching.platform.modules.student.application.port.out;

import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;

import java.util.Optional;
import java.util.UUID;

public interface StudentProfilePort {

    Optional<StudentProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    StudentProfile save(StudentProfile profile);

    StudentProfile update(StudentProfile profile);
}
