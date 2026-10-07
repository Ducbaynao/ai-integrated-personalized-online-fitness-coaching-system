import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { CoachingState } from '@/features/coaching/components/CoachingUi';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';

export default function NewWorkoutPlanRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(params.relationshipId);
  const relationship = useRelationshipDetail(relationshipId ?? '');
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as any} />;
  if (relationship.isPending) return <CoachingState busy title="Đang tải quan hệ" message="Vui lòng chờ trong giây lát." />;
  if (relationship.isError || !relationship.data?.relationship) return <CoachingState title="Không thể mở trình tạo kế hoạch" message="Quan hệ không tồn tại hoặc bạn không còn quyền truy cập." onRetry={() => relationship.refetch()} />;
  return <WorkoutPlanBuilderScreen mode="create" relationshipId={relationshipId} studentId={relationship.data.relationship.studentId} />;
}
