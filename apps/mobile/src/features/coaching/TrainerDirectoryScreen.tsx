import React, { useRef, useState } from 'react';
import { ScrollView, Text, TextInput, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { coachingQueryKeys, useTrainerDirectory } from './coachingQueries';
import { coachingErrorMessage } from './coachingMessages';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';

export function TrainerDirectoryScreen() {
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search);
  const query = useTrainerDirectory(debounced);
  const queryClient = useQueryClient();
  const router = useRouter();
  const commandKeys = useRef<Record<string, string>>({});
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const requestMutation = useMutation({ mutationFn: ({ trainerId, commandKey }: { trainerId: string; commandKey: string }) => coachingApi.requestTrainer(trainerId, commandKey), onSuccess: async (result, variables) => {
    delete commandKeys.current[variables.trainerId];
    await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.relationships() });
    if (result.relationship) router.replace(`/coaching/${result.relationship.id}` as never);
  }});
  return <ScrollView testID="trainer-directory-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Tìm huấn luyện viên</Text>
    <Text style={[styles.label, { color: theme.textPrimary }]}>Tên hiển thị</Text>
    <TextInput testID="trainer-search-input" accessibilityLabel="Tìm theo tên huấn luyện viên" value={search} onChangeText={setSearch}
      placeholder="Nhập tên huấn luyện viên" placeholderTextColor={theme.textSecondary} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    {query.isPending ? <CoachingState busy title="Đang tìm huấn luyện viên" message="Danh sách được lọc bởi hệ thống." /> : null}
    {query.isError ? <CoachingState title="Chưa thể tải danh sách" message={coachingErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <CoachingState testID="trainer-no-results" title={debounced ? 'Không có kết quả' : 'Chưa có huấn luyện viên khả dụng'} message={debounced ? 'Hãy thử tên hiển thị khác.' : 'Vui lòng quay lại sau.'} /> : null}
    {requestMutation.isError ? <CoachingState testID="request-trainer-error" title="Chưa thể gửi yêu cầu" message={coachingErrorMessage(requestMutation.error)} /> : null}
    {items.map((trainer) => <CoachingCard key={trainer.trainerId} testID={`trainer-${trainer.trainerId}`}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{trainer.displayName}</Text>
      <CoachingButton label={`Gửi yêu cầu đến ${trainer.displayName}`} busy={requestMutation.isPending} disabled={requestMutation.isPending} onPress={() => {
        const commandKey = commandKeys.current[trainer.trainerId] ?? createCommandKey();
        commandKeys.current[trainer.trainerId] = commandKey;
        requestMutation.mutate({ trainerId: trainer.trainerId, commandKey });
      }} />
    </CoachingCard>)}
    {query.hasNextPage ? <CoachingButton label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm'} busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} secondary /> : null}
  </ScrollView>;
}

