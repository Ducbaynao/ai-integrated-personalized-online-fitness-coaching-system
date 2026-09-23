package com.fitnesscoaching.platform.modules.student.adapter.out;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentProfileNotFoundException;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.student.application.port.out.StudentProfilePort;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StudentGoalAuthorityAdapter implements GoalStudentAuthorityPort {

    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final StudentProfilePort studentProfilePort;

    public StudentGoalAuthorityAdapter(
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            StudentProfilePort studentProfilePort
    ) {
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.studentProfilePort = studentProfilePort;
    }

    @Override
    public void verifyStudentCanManageGoals(UUID studentId) {
        if (studentId == null) {
            throw new StudentProfileNotFoundException("Student ID cannot be null");
        }

        AccountStatus accountStatus = userAccountStatusQuery.getAccountStatus(studentId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + studentId));

        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException(
                    "Account status " + accountStatus + " does not permit this operation.");
        }

        if (!userRoleQuery.hasActiveRole(studentId, "STUDENT")) {
            throw new StudentCapabilityUnavailableException(
                    "Student capability is not active for user: " + studentId);
        }

        if (!studentProfilePort.existsByUserId(studentId)) {
            throw new StudentProfileNotFoundException(
                    "Student profile not found for user: " + studentId);
        }
    }
}
