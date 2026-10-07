import { QueryClient } from '@tanstack/react-query';
import { workoutPlanQueryKeys } from './workoutPlanQueries';

export function removeStudentWorkoutPlanCache(queryClient: QueryClient, studentId: string): void {
  queryClient.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
}
