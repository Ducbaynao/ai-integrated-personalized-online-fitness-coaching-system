import React from 'react';
import { RefreshControl, ScrollView, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationships } from './coachingQueries';
import { coachingErrorMessage, coachingStatusLabels } from './coachingMessages';
import { coachingUiStyles as styles } from './components/CoachingUi';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { NavigationAction } from '@/design-system/components/NavigationAction';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout } from '@/design-system/tokens/spacing';

export function CoachingHubScreen() {
  const router = useRouter();
  const { activeCapability, user } = useAuth();
  const query = useRelationships();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const relationships = (query.data?.pages.flatMap((page) => page.items) ?? []).filter((relationship) =>
    activeCapability === 'TRAINER' ? relationship.trainerId === user?.id : relationship.studentId === user?.id
  );

  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="coaching-hub-screen" style={styles.screen}
    contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]} refreshControl={<RefreshControl refreshing={query.isRefetching} onRefresh={() => query.refetch()} />}>
    <View>
      <Text accessibilityRole="header" aria-level={1} style={[styles.title, { color: theme.textPrimary }]}>Huấn luyện cá nhân</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{activeCapability === 'TRAINER' ? 'Yêu cầu, học viên và quyền dữ liệu hiện tại.' : 'Huấn luyện viên, trạng thái quan hệ và quyền chia sẻ của bạn.'}</Text>
    </View>
    <AppCard title="Kết nối huấn luyện" icon="coaching">
      {activeCapability === 'STUDENT' || user?.capabilities?.canCoach ? (
        <AppButton testID="open-qr-scanner-button" icon="coaching" label={activeCapability === 'TRAINER' ? 'Tìm học viên qua QR' : 'Tìm huấn luyện viên qua QR'}
          onPress={() => router.push({ pathname: '/coaching/qr', params: { targetRole: activeCapability === 'TRAINER' ? 'STUDENT' : 'TRAINER' } } as never)} />
      ) : null}
      <AppButton testID="open-my-qr-button" icon="profile" label="Mã QR của tôi" variant="secondary"
        onPress={() => router.push('/coaching/my-qr' as never)} />
    </AppCard>
    <View style={styles.row}>
      <NavigationAction title="Tin nhắn" icon="chat" tile accessibilityLabel="Tin nhắn" onPress={() => router.push('/chat' as never)} />
      <NavigationAction title="Thông báo" icon="bell" tile accessibilityLabel="Thông báo" onPress={() => router.push('/notifications' as never)} />
    </View>
      {activeCapability === 'STUDENT' ? <NavigationAction testID="open-attention-button" title="Đánh giá và tín hiệu chú ý" accessibilityLabel="Đánh giá và tín hiệu chú ý" icon="attention"
        onPress={() => router.push('/attention' as never)} /> : null}
    {activeCapability === 'TRAINER' && !user?.capabilities?.canCoach
      ? <AppCard state="info" testID="trainer-ineligible" title="Chưa thể mời học viên" message="Quyền huấn luyện hiện chưa có hiệu lực. Bạn vẫn có thể xử lý các quan hệ hiện có." />
      : null}
    {query.isPending ? <AppCard testID="relationships-loading" state="loading" title="Đang tải quan hệ" message="Vui lòng chờ trong giây lát." /> : null}
    {query.isError ? <AppCard state="error" testID="relationships-error" title="Chưa thể tải quan hệ" message={coachingErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && relationships.length === 0 ? <AppCard state="empty" testID="relationships-empty" title="Chưa có quan hệ huấn luyện" message={activeCapability === 'TRAINER' ? 'Các lời mời và quan hệ được phép xem sẽ xuất hiện tại đây.' : 'Bạn có thể tìm huấn luyện viên hoặc chờ lời mời.'} /> : null}
    <Text accessibilityRole="header" aria-level={2} style={[styles.h2, { color: theme.textPrimary }]}>Lời mời và quan hệ</Text>
    {relationships.map((relationship) => <NavigationAction key={relationship.id} testID={`relationship-${relationship.id}`}
      icon={relationship.status === 'PENDING' ? 'coaching' : relationship.status === 'PAUSED' ? 'pause' : 'profile'}
      title={relationship.counterparty.displayName}
      description={`${coachingStatusLabels[relationship.status]} · ${relationship.direction === 'INCOMING' ? 'Lời mời/yêu cầu đến' : 'Yêu cầu đã gửi'}`}
      accessibilityLabel={`Mở quan hệ với ${relationship.counterparty.displayName}, ${coachingStatusLabels[relationship.status]}`}
      onPress={() => router.push(`/coaching/${relationship.id}` as never)} />)}
    {query.hasNextPage ? <AppButton icon="plus" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm'} loading={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} variant="secondary" /> : null}
  </ScrollView></SafeAreaView>;
}

