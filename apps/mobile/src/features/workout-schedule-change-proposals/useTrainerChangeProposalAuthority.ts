import { useCallback, useEffect, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { workoutScheduleChangeProposalQueryKeys } from './workoutScheduleChangeProposalQueries';

export function useTrainerChangeProposalAuthority(relationshipId: string) {
  const { user, activeCapability } = useAuth();
  const client = useQueryClient();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const refetchRelationship = relationshipQuery.refetch;
  const refetchSharing = sharingQuery.refetch;
  const relationship = relationshipQuery.data?.relationship;
  const trainerId = user?.id ?? '';
  const studentId = relationship?.studentId ?? '';
  const scope = `${trainerId}:${relationshipId}:${studentId}`;
  const [verifiedScope, setVerifiedScope] = useState('');
  const [deniedScope, setDeniedScope] = useState('');
  const grant = sharingQuery.data?.items.find((entry) => entry.dataScope === 'WORKOUT_PLAN');
  const current = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId
    && relationship.status === 'ACTIVE' && grant?.state === 'ALLOWED';
  const hasView = current && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const hasContribute = current && ['CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const canView = Boolean(hasView && verifiedScope === scope && deniedScope !== scope);
  const canContribute = Boolean(hasContribute && canView);

  const clear = useCallback((subjectId: string) => {
    if (!trainerId || !subjectId) return;
    client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.trainer(trainerId, subjectId) });
    client.removeQueries({ queryKey: workoutScheduleQueryKeys.trainer(trainerId, subjectId) });
  }, [client, trainerId]);

  const deny = useCallback(() => {
    clear(studentId);
    setVerifiedScope('');
    setDeniedScope(scope);
  }, [clear, scope, studentId]);

  const verify = useCallback(async (isActive: () => boolean) => {
    setVerifiedScope('');
    if (activeCapability !== 'TRAINER' || !trainerId) {
      if (isActive()) deny();
      return;
    }
    try {
      const [relationResult, sharingResult] = await Promise.all([refetchRelationship(), refetchSharing()]);
      if (!isActive()) return;
      const fresh = relationResult.data?.relationship;
      const permission = sharingResult.data?.items.find((entry) => entry.dataScope === 'WORKOUT_PLAN');
      if (!relationResult.isError && !sharingResult.isError && fresh?.trainerId === trainerId
        && fresh.status === 'ACTIVE' && permission?.state === 'ALLOWED'
        && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(permission.accessLevel ?? '')) {
        setDeniedScope('');
        setVerifiedScope(`${trainerId}:${relationshipId}:${fresh.studentId}`);
      } else {
        clear(studentId);
        clear(fresh?.studentId ?? '');
        setDeniedScope(scope);
      }
    } catch {
      if (isActive()) deny();
    }
  }, [activeCapability, clear, deny, relationshipId, refetchRelationship, refetchSharing, scope, studentId, trainerId]);

  useFocusEffect(useCallback(() => {
    let active = true;
    void verify(() => active);
    return () => { active = false; clear(studentId); };
  }, [clear, studentId, verify]));

  useEffect(() => {
    if (studentId && !hasView) clear(studentId);
  }, [clear, hasView, studentId]);

  return {
    relationship, relationshipQuery, sharingQuery, studentId, trainerId, activeCapability,
    canView, canContribute, verifying: verifiedScope !== scope && deniedScope !== scope,
    denied: deniedScope === scope,
    deny, retry: () => { setDeniedScope(''); void verify(() => true); },
  };
}
