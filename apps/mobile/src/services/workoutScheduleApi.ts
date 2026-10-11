import { request } from '@/services/apiClient';
import type { PlannedWorkoutOccurrencePage } from '@/types/workoutSchedule';

export const WORKOUT_SCHEDULE_PAGE_SIZE = 20;

export const workoutScheduleApi = {
  list(from: string, until: string, page = 0, size = WORKOUT_SCHEDULE_PAGE_SIZE) {
    const params = new URLSearchParams({ from, until, page: String(page), size: String(size) });
    return request<PlannedWorkoutOccurrencePage>(`/planned-workouts?${params}`, { method: 'GET' });
  },
};
