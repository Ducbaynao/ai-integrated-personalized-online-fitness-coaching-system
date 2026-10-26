import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { proposalTimezones } from '@/features/workout-schedule-proposals/proposalTime';
import { pushApi, pushCategories, PushCategory, PushPreferenceInput } from '@/services/pushApi';

export default function PushSettingsScreen() {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const cache = useQueryClient();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const queryKey = ['push-preferences', actor, role];
  const query = useQuery({ queryKey, enabled: !!actor, gcTime: 0, queryFn: pushApi.list });
  const [search, setSearch] = useState('');
  const [timezone, setTimezone] = useState('');
  const [quietStart, setQuietStart] = useState('');
  const [quietEnd, setQuietEnd] = useState('');
  const [formError, setFormError] = useState('');
  const zones = useMemo(() => search.trim().length < 2 ? [] : proposalTimezones(search).slice(0, 12), [search]);
  const mutation = useMutation({ mutationFn: ({ category, input }: { category: PushCategory; input: PushPreferenceInput }) => pushApi.set(category, input),
    onSuccess: () => { setFormError(''); void cache.invalidateQueries({ queryKey }); },
    onError: error => {
      if (error instanceof ApiError && [403, 404].includes(error.status)) cache.removeQueries({ queryKey });
      setFormError(error instanceof Error ? error.message : 'Không thể lưu tùy chọn.');
    },
  });
  const save = (category: PushCategory, enabled: boolean) => {
    if (!timezone) { setFormError('Hãy chọn và xác nhận múi giờ IANA trước khi lưu.'); return; }
    if ((quietStart === '') !== (quietEnd === '') || (quietStart && (!/^\d{2}:\d{2}$/.test(quietStart)
      || !/^\d{2}:\d{2}$/.test(quietEnd) || quietStart === quietEnd))) {
      setFormError('Giờ yên tĩnh cần hai mốc HH:mm khác nhau hoặc để trống cả hai.'); return;
    }
    mutation.mutate({ category, input: { enabled, timezone,
      quietHoursStart: quietStart || null, quietHoursEnd: quietEnd || null } });
  };
  const concealed = query.isError && query.error instanceof ApiError && [403, 404].includes(query.error.status);
  useEffect(() => { if (concealed) cache.removeQueries({ queryKey: ['push-preferences', actor, role] }); }, [concealed, cache, actor, role]);
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
    <ScrollView testID="push-settings" contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: theme.textPrimary }]}>Thông báo đẩy</Text>
      <CoachingButton label="Quay lại thông báo" secondary onPress={() => router.back()} />
      <Text style={[styles.body, { color: theme.textSecondary }]}>Mặc định tắt. Hộp thư trong ứng dụng vẫn hoạt động. Expo xác nhận ticket và nhà cung cấp; chúng không chứng minh thiết bị đã nhận hoặc bạn đã đọc.</Text>
      {query.isPending ? <CoachingState busy title="Đang tải tùy chọn" message="Vui lòng chờ." /> : null}
      {query.isError ? <CoachingState title="Không thể tải tùy chọn" message="Vui lòng thử lại." onRetry={() => query.refetch()} /> : null}
      {!query.isError ? <View>
        <TextInput accessibilityLabel="Tìm múi giờ IANA" placeholder="Tìm, ví dụ Asia/Ho_Chi_Minh"
          value={search} onChangeText={setSearch} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        {zones.map(zone => <CoachingButton key={zone} label={zone} secondary={zone !== timezone}
          onPress={() => setTimezone(zone)} />)}
        <Text style={[styles.label, { color: theme.textPrimary }]}>Múi giờ đã chọn: {timezone || 'Chưa chọn'}</Text>
        <TextInput accessibilityLabel="Giờ bắt đầu yên tĩnh HH:mm" placeholder="Bắt đầu HH:mm (có thể bỏ trống)"
          value={quietStart} onChangeText={setQuietStart} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        <TextInput accessibilityLabel="Giờ kết thúc yên tĩnh HH:mm" placeholder="Kết thúc HH:mm (có thể bỏ trống)"
          value={quietEnd} onChangeText={setQuietEnd} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        <Text style={[styles.body, { color: theme.textSecondary }]}>Trong khoảng giờ yên tĩnh, thông báo đẩy chờ tới thời điểm được phép. Không có nhắc hẹn theo giờ trong luồng này.</Text>
      </View> : null}
      {formError ? <CoachingState title="Chưa lưu được" message={formError} /> : null}
      {!query.isPending && !query.isError ? pushCategories.map(([category, title]) => {
        const current = query.data?.find(item => item.eventType === category);
        return <CoachingCard key={category}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>{title}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Push: {current?.enabled ? 'Đang bật' : 'Đang tắt'}{current?.timezone ? ` · ${current.timezone}` : ''}</Text>
          <CoachingButton label={current?.enabled ? `Tắt push ${title}` : `Bật push ${title}`}
            busy={mutation.isPending} disabled={mutation.isPending}
            onPress={() => save(category, !current?.enabled)} />
        </CoachingCard>;
      }) : null}
    </ScrollView>
  </SafeAreaView>;
}
