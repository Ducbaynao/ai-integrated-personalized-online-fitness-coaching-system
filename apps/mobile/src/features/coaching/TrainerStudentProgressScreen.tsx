import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { typography } from '@/design-system/tokens/typography';
import { ProgressDashboardScreen } from '@/features/progress/ProgressDashboardScreen';
import { useRelationshipDetail } from './coachingQueries';

interface TrainerStudentProgressScreenProps {
  relationshipId: string;
}

export function TrainerStudentProgressScreen({ relationshipId }: TrainerStudentProgressScreenProps) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const relationship = relationshipQuery.data?.relationship ?? null;
  const studentId = relationship?.studentId ?? null;

  if (relationshipQuery.isLoading) {
    return (
      <View style={[styles.center, { backgroundColor: theme.canvas }]}>
        <ActivityIndicator size="large" color={theme.primary} />
        <Text style={[styles.loadingText, { color: theme.textSecondary }]}>Đang tải thông tin học viên...</Text>
      </View>
    );
  }

  if (relationshipQuery.isError || !relationship || !studentId) {
    return (
      <View style={[styles.center, { backgroundColor: theme.canvas }]}>
        <Text style={[styles.errorText, { color: theme.dangerText }]}>
          Không tìm thấy thông tin mối quan hệ huấn luyện
        </Text>
      </View>
    );
  }

  return <ProgressDashboardScreen studentId={studentId} readOnly={false} />;
}

const styles = StyleSheet.create({
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  loadingText: {
    ...typography.bodySmall,
    marginTop: 12,
  },
  errorText: {
    ...typography.body,
    fontWeight: '600',
    textAlign: 'center',
  },
});
