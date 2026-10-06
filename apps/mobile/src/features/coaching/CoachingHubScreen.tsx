import React from 'react';
import { Pressable, RefreshControl, ScrollView, Text, View, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationships } from './coachingQueries';
import { coachingErrorMessage, coachingStatusLabels } from './coachingMessages';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';

export function CoachingHubScreen() {
  const router = useRouter();
  const { activeCapability, user } = useAuth();
  const query = useRelationships();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const relationships = (query.data?.pages.flatMap((page) => page.items) ?? []).filter((relationship) =>
    activeCapability === 'TRAINER' ? relationship.trainerId === user?.id : relationship.studentId === user?.id
  );

  return <ScrollView testID="coaching-hub-screen" style={[styles.screen, { backgroundColor: theme.canvas }]}
    contentContainerStyle={styles.content} refreshControl={<RefreshControl refreshing={query.isRefetching} onRefresh={() => query.refetch()} />}>
    <View>
      <Text style={[styles.title, { color: theme.textPrimary }]}>Huấn luyện cá nhân</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{activeCapability === 'TRAINER' ? 'Yêu cầu, học viên và quyền dữ liệu hiện tại.' : 'Huấn luyện viên, trạng thái quan hệ và quyền chia sẻ của bạn.'}</Text>
    </View>
    {activeCapability === 'STUDENT'
      ? <CoachingButton testID="browse-trainers-button" label="Tìm huấn luyện viên" onPress={() => router.push('/coaching/trainers' as never)} />
      : user?.capabilities?.canCoach
        ? <CoachingButton testID="lookup-student-button" label="Mời học viên bằng email" onPress={() => router.push('/coaching/invite' as never)} />
        : <CoachingState testID="trainer-ineligible" title="Chưa thể mời học viên" message="Quyền huấn luyện hiện chưa có hiệu lực. Bạn vẫn có thể xử lý các quan hệ hiện có." />}
    {query.isPending ? <CoachingState testID="relationships-loading" busy title="Đang tải quan hệ" message="Vui lòng chờ trong giây lát." /> : null}
    {query.isError ? <CoachingState testID="relationships-error" title="Chưa thể tải quan hệ" message={coachingErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && relationships.length === 0 ? <CoachingState testID="relationships-empty" title="Chưa có quan hệ huấn luyện" message={activeCapability === 'TRAINER' ? 'Các lời mời và quan hệ được phép xem sẽ xuất hiện tại đây.' : 'Bạn có thể tìm huấn luyện viên hoặc chờ lời mời.'} /> : null}
    {relationships.map((relationship) => <Pressable key={relationship.id} testID={`relationship-${relationship.id}`} accessibilityRole="button"
      accessibilityLabel={`Mở quan hệ với ${relationship.counterparty.displayName}, ${coachingStatusLabels[relationship.status]}`}
      onPress={() => router.push(`/coaching/${relationship.id}` as never)}>
      <CoachingCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{relationship.counterparty.displayName}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{coachingStatusLabels[relationship.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{relationship.direction === 'INCOMING' ? 'Lời mời/yêu cầu đến' : 'Yêu cầu đã gửi'}</Text>
      </CoachingCard>
    </Pressable>)}
    {query.hasNextPage ? <CoachingButton label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm'} busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} secondary /> : null}
  </ScrollView>;
}

