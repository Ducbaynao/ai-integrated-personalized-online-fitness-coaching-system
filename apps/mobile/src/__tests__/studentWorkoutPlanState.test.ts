import { ApiError } from '@/types/auth';
import { workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { isCurrentPlanMissing, isWorkoutStaleConflict, workoutPlanErrorMessage } from '@/features/workout-plan/workoutPlanMessages';

const response = (errorCode: string) => ({ errorCode, message: errorCode, timestamp: '', requestId: '', fieldErrors: [] });
describe('Student workout plan state', () => {
  it('scopes all private cache keys by Student identity', () => {
    expect(workoutPlanQueryKeys.current('a')).not.toEqual(workoutPlanQueryKeys.current('b'));
    expect(workoutPlanQueryKeys.version('a', 'p', 'v')).toEqual(['workout-plans', 'student', 'a', 'detail', 'p', 'versions', 'v']);
  });
  it('distinguishes current-plan absence from retryable failures', () => {
    expect(isCurrentPlanMissing(new ApiError(404, 'missing'))).toBe(true);
    expect(isCurrentPlanMissing(new ApiError(500, 'server'))).toBe(false);
  });
  it('maps stable errors to Vietnamese and classifies stale reloads', () => {
    expect(workoutPlanErrorMessage(new ApiError(409, 'x', response('ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS')))).toContain('đang áp dụng');
    expect(workoutPlanErrorMessage(new ApiError(409, 'x', response('WORKOUT_PLAN_SUCCESSOR_REQUIRED')))).toContain('kế nhiệm');
    expect(isWorkoutStaleConflict(new ApiError(409, 'x', response('WORKOUT_PLAN_VERSION_CONFLICT')))).toBe(true);
  });

  it.each([
    'WORKOUT_PLAN_NOT_FOUND', 'WORKOUT_PLAN_VERSION_NOT_FOUND', 'PLANNED_WORKOUT_NOT_FOUND',
    'WORKOUT_PLAN_ACCESS_DENIED', 'COACHING_PERIOD_REQUIRED', 'WORKOUT_PLAN_LIFECYCLE_CONFLICT',
    'ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS', 'WORKOUT_PLAN_VERSION_CONFLICT', 'PLANNED_WORKOUT_VERSION_CONFLICT',
    'WORKOUT_PLAN_IDEMPOTENCY_CONFLICT', 'WORKOUT_PLAN_IMMUTABLE', 'WORKOUT_PLAN_SUCCESSOR_REQUIRED',
    'WORKOUT_PLAN_EXERCISE_UNAVAILABLE', 'WORKOUT_PLAN_EFFECTIVE_TIME_CONFLICT', 'VALIDATION_FAILED',
  ])('does not expose backend error text for %s', (code) => {
    const message = workoutPlanErrorMessage(new ApiError(409, 'raw backend detail', response(code)));
    expect(message).not.toContain(code);
    expect(message).not.toContain('raw backend detail');
  });
});
