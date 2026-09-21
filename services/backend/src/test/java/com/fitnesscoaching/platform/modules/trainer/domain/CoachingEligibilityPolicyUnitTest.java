package com.fitnesscoaching.platform.modules.trainer.domain;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoachingEligibilityPolicyUnitTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TrainerProfile createProfile(
            TrainerVerificationStatus verificationStatus,
            TrainerActivityStatus activityStatus,
            boolean isActive,
            boolean isAcceptingStudents
    ) {
        return new TrainerProfile(
                USER_ID,
                "coach-slug",
                "Trainer bio",
                BigDecimal.valueOf(5),
                isAcceptingStudents,
                verificationStatus,
                activityStatus,
                Instant.now(),
                UUID.randomUUID(),
                isActive,
                Instant.now(),
                Instant.now()
        );
    }

    private TrainerProfile createVerifiedActiveProfile(boolean isAcceptingStudents) {
        return createProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.ACTIVE, true, isAcceptingStudents);
    }

    @Test
    @DisplayName("Eligible is TRUE when all conditions are satisfied simultaneously")
    void evaluate_allConditionsSatisfied_eligibleIsTrue() {
        TrainerProfile profile = createVerifiedActiveProfile(true);

        CoachingEligibility result = CoachingEligibility.evaluate(
                AccountStatus.ACTIVE,
                true,
                profile
        );

        assertThat(result.eligible()).isTrue();
        assertThat(result.blockingReasons()).isEmpty();
    }

    @Test
    @DisplayName("isAcceptingStudents (true or false) does not affect coaching eligibility")
    void evaluate_isAcceptingStudents_doesNotAffectEligibility() {
        TrainerProfile profileAccepting = createVerifiedActiveProfile(true);
        TrainerProfile profileNotAccepting = createVerifiedActiveProfile(false);

        CoachingEligibility resultAccepting = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profileAccepting);
        CoachingEligibility resultNotAccepting = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profileNotAccepting);

        assertThat(resultAccepting.eligible()).isTrue();
        assertThat(resultAccepting.blockingReasons()).isEmpty();

        assertThat(resultNotAccepting.eligible()).isTrue();
        assertThat(resultNotAccepting.blockingReasons()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"ACTIVE"}, mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    @NullSource
    @DisplayName("Non-ACTIVE account status blocks eligibility with ACCOUNT_NOT_ACTIVE")
    void evaluate_nonActiveAccount_blocksEligibility(AccountStatus nonActiveStatus) {
        TrainerProfile profile = createVerifiedActiveProfile(true);

        CoachingEligibility result = CoachingEligibility.evaluate(
                nonActiveStatus,
                true,
                profile
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("Missing or revoked TRAINER role blocks eligibility with TRAINER_ROLE_MISSING")
    void evaluate_missingOrRevokedRole_blocksEligibility() {
        TrainerProfile profile = createVerifiedActiveProfile(true);

        CoachingEligibility result = CoachingEligibility.evaluate(
                AccountStatus.ACTIVE,
                false,
                profile
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("TRAINER_ROLE_MISSING");
    }

    @Test
    @DisplayName("Missing Trainer Profile blocks eligibility with PROFILE_NOT_FOUND")
    void evaluate_missingProfile_blocksEligibility() {
        CoachingEligibility result = CoachingEligibility.evaluate(
                AccountStatus.ACTIVE,
                true,
                null
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("PROFILE_NOT_FOUND");
    }

    @Test
    @DisplayName("Inactive Trainer Profile (isActive = false) blocks eligibility with PROFILE_INACTIVE")
    void evaluate_inactiveProfile_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.ACTIVE, false, true);

        CoachingEligibility result = CoachingEligibility.evaluate(
                AccountStatus.ACTIVE,
                true,
                profile
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("PROFILE_INACTIVE");
    }

    @Test
    @DisplayName("Verification status NOT_SUBMITTED blocks eligibility with APPLICATION_NOT_SUBMITTED")
    void evaluate_verificationStatusNotSubmitted_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.NOT_SUBMITTED, TrainerActivityStatus.ACTIVE, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("APPLICATION_NOT_SUBMITTED");
    }

    @Test
    @DisplayName("Verification status PENDING blocks eligibility with VERIFICATION_PENDING")
    void evaluate_verificationStatusPending_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.PENDING, TrainerActivityStatus.ACTIVE, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("VERIFICATION_PENDING");
    }

    @Test
    @DisplayName("Verification status REJECTED blocks eligibility with VERIFICATION_REJECTED")
    void evaluate_verificationStatusRejected_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.REJECTED, TrainerActivityStatus.ACTIVE, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("VERIFICATION_REJECTED");
    }

    @Test
    @DisplayName("Verification status SUSPENDED blocks eligibility with VERIFICATION_REVOKED")
    void evaluate_verificationStatusSuspended_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.SUSPENDED, TrainerActivityStatus.ACTIVE, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("VERIFICATION_REVOKED");
    }

    @Test
    @DisplayName("Activity status INACTIVE blocks eligibility with ACTIVITY_INACTIVE")
    void evaluate_activityStatusInactive_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.INACTIVE, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("ACTIVITY_INACTIVE");
    }

    @Test
    @DisplayName("Activity status SUSPENDED blocks eligibility with ACTIVITY_SUSPENDED")
    void evaluate_activityStatusSuspended_blocksEligibility() {
        TrainerProfile profile = createProfile(TrainerVerificationStatus.VERIFIED, TrainerActivityStatus.SUSPENDED, true, true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("ACTIVITY_SUSPENDED");
    }

    @Test
    @DisplayName("Policy restriction flag blocks eligibility with POLICY_RESTRICTION")
    void evaluate_policyRestriction_blocksEligibility() {
        TrainerProfile profile = createVerifiedActiveProfile(true);

        CoachingEligibility result = CoachingEligibility.evaluate(AccountStatus.ACTIVE, true, profile, true);

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).contains("POLICY_RESTRICTION");
    }

    @Test
    @DisplayName("Deterministic ordering: multiple blocking reasons follow account, role, profile, verification, activity, restriction")
    void evaluate_multipleBlockingReasons_followDeterministicOrder() {
        TrainerProfile badProfile = createProfile(
                TrainerVerificationStatus.NOT_SUBMITTED,
                TrainerActivityStatus.INACTIVE,
                false,
                true
        );

        CoachingEligibility result = CoachingEligibility.evaluate(
                AccountStatus.SUSPENDED,
                false,
                badProfile,
                true
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.blockingReasons()).containsExactly(
                "ACCOUNT_NOT_ACTIVE",
                "TRAINER_ROLE_MISSING",
                "PROFILE_INACTIVE",
                "APPLICATION_NOT_SUBMITTED",
                "ACTIVITY_INACTIVE",
                "POLICY_RESTRICTION"
        );
    }

    @Test
    @DisplayName("Record immutability: mutating blockingReasons throws UnsupportedOperationException")
    void blockingReasons_isImmutable() {
        CoachingEligibility eligibility = CoachingEligibility.evaluate(
                AccountStatus.ACTIVE,
                false,
                null
        );

        assertThatThrownBy(() -> eligibility.blockingReasons().add("NEW_REASON"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Domain invariant: eligible=true with non-empty blockingReasons throws IllegalArgumentException")
    void domainInvariant_eligibleTrueWithBlockingReasons_throwsException() {
        assertThatThrownBy(() -> new CoachingEligibility(true, List.of("SOME_REASON")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Eligible coaching status cannot have blocking reasons");
    }

    @Test
    @DisplayName("Domain invariant: eligible=false with empty blockingReasons throws IllegalArgumentException")
    void domainInvariant_eligibleFalseWithEmptyBlockingReasons_throwsException() {
        assertThatThrownBy(() -> new CoachingEligibility(false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ineligible coaching status must specify at least one blocking reason");
    }

    @Test
    @DisplayName("Domain invariant: eligible=false with null blockingReasons throws IllegalArgumentException")
    void domainInvariant_eligibleFalseWithNullBlockingReasons_throwsException() {
        assertThatThrownBy(() -> new CoachingEligibility(false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ineligible coaching status must specify at least one blocking reason");
    }
}
