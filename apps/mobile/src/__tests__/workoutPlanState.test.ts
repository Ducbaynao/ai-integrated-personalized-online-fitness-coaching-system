import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { removeStudentWorkoutPlanCache } from '@/features/workout-plan/workoutPlanCache';
import { workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { isWorkoutAuthorityLoss, isWorkoutStaleConflict, workoutPlanErrorMessage } from '@/features/workout-plan/workoutPlanMessages';

describe('workout plan state and privacy', () => {
  it('includes Student identity in every sensitive plan key', () => {
    expect(workoutPlanQueryKeys.detail('student-a', 'plan-1')).not.toEqual(workoutPlanQueryKeys.detail('student-b', 'plan-1'));
    expect(workoutPlanQueryKeys.version('student-a', 'plan-1', 'v1')).toEqual(['workout-plans', 'student', 'student-a', 'detail', 'plan-1', 'versions', 'v1']);
  });

  it('purges only the selected Student workout cache after authority loss', () => {
    const client = new QueryClient();
    client.setQueryData(workoutPlanQueryKeys.detail('student-a', 'plan-1'), { secret: 'a' });
    client.setQueryData(workoutPlanQueryKeys.detail('student-b', 'plan-2'), { secret: 'b' });
    removeStudentWorkoutPlanCache(client, 'student-a');
    expect(client.getQueryData(workoutPlanQueryKeys.detail('student-a', 'plan-1'))).toBeUndefined();
    expect(client.getQueryData(workoutPlanQueryKeys.detail('student-b', 'plan-2'))).toEqual({ secret: 'b' });
    client.clear();
  });

  it('classifies concealment and stale conflicts without loosening errors', () => {
    expect(isWorkoutAuthorityLoss(new ApiError(404, 'hidden'))).toBe(true);
    expect(isWorkoutAuthorityLoss(new ApiError(403, 'denied'))).toBe(true);
    expect(isWorkoutStaleConflict(new ApiError(409, 'stale', { errorCode: 'WORKOUT_PLAN_VERSION_CONFLICT', message: 'stale', timestamp: '', requestId: '', fieldErrors: [] }))).toBe(true);
    expect(workoutPlanErrorMessage(new ApiError(409, 'active', { errorCode: 'ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS', message: 'active', timestamp: '', requestId: '', fieldErrors: [] }))).toContain('đang áp dụng');
  });
});
