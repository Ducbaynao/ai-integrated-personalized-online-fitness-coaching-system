import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { formatTimezoneDisplay, resolveNewRecordDefaultTimezone } from '@/features/shared/timezoneUtils';
import { pushApi, pushCategories, PushCategory, PushPreferenceInput, reminderApi, ReminderCategory } from '@/services/pushApi';

interface ConfirmReceipt {
  sessionToken: symbol;
  contextVersion: number;
  actor: string;
  role: string;
  category: PushCategory;
  timezone: string;
  quietHoursStart: string;
  quietHoursEnd: string;
}

export default function PushSettingsScreen() {
  const { user, activeCapability, status } = useAuth();
  // Remount cleanly whenever actor/capability/status transitions (including A -> B -> A or role switches)
  // so every previous session's confirmation callbacks and async continuations become permanently inert.
  const sessionKey = `${user?.id ?? ''}:${activeCapability ?? ''}:${status ?? ''}`;
  return <PushSettingsSession key={sessionKey} />;
}

function PushSettingsSession() {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const cache = useQueryClient();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const queryKey = ['push-preferences', actor, role];
  const query = useQuery({ queryKey, enabled: !!actor, gcTime: 0, queryFn: pushApi.list });
  const reminderKey = ['reminder-preferences', actor, role];
  const reminderQuery = useQuery({ queryKey: reminderKey, enabled: !!actor, gcTime: 0, queryFn: reminderApi.list });

  const mountedRef = useRef(true);
  const activePushOpRef = useRef<symbol | null>(null);

  const [sessionToken] = useState<symbol>(() => Symbol('push-session'));
  const [contextVersion, setContextVersion] = useState(0);
  const [selectedZone, setSelectedZone] = useState(() => resolveNewRecordDefaultTimezone());
  const [targetCategory, setTargetCategory] = useState<PushCategory>(() => (pushCategories[0]?.[0] ?? 'CHAT') as PushCategory);
  const [confirmations, setConfirmations] = useState<Partial<Record<PushCategory, ConfirmReceipt>>>({});
  const [pickerExpanded, setPickerExpanded] = useState(false);
  const [quietStart, setQuietStart] = useState('');
  const [quietEnd, setQuietEnd] = useState('');
  const [formError, setFormError] = useState('');
  const [savingPushOp, setSavingPushOp] = useState<symbol | null>(null);

  const liveRef = useRef<{
    sessionToken: symbol;
    contextVersion: number;
    actor: string;
    role: string;
    selectedZone: string;
    quietStart: string;
    quietEnd: string;
  } | null>(null);

  useLayoutEffect(() => {
    mountedRef.current = true;
    liveRef.current = { sessionToken, contextVersion, actor, role, selectedZone, quietStart, quietEnd };
  }, [actor, contextVersion, quietEnd, quietStart, role, selectedZone, sessionToken]);

  useLayoutEffect(() => {
    return () => {
      mountedRef.current = false;
      liveRef.current = null;
      activePushOpRef.current = null;
    };
  }, []);

  const invalidateContext = () => {
    const nextVersion = contextVersion + 1;
    if (liveRef.current) {
      liveRef.current = { ...liveRef.current, contextVersion: nextVersion };
    }
    setContextVersion(nextVersion);
    activePushOpRef.current = null;
    setSavingPushOp(null);
    setConfirmations({});
    setFormError('');
  };

  const isRenderConfirmed = (receipt: ConfirmReceipt | undefined, category: PushCategory): receipt is ConfirmReceipt => {
    if (!receipt) return false;
    return (
      receipt.sessionToken === sessionToken &&
      receipt.contextVersion === contextVersion &&
      receipt.actor === actor &&
      receipt.role === role &&
      receipt.category === category &&
      receipt.timezone === selectedZone &&
      receipt.quietHoursStart === quietStart &&
      receipt.quietHoursEnd === quietEnd
    );
  };

  const isHandlerSnapshotValid = useCallback((snapshot: ConfirmReceipt | null | undefined): snapshot is ConfirmReceipt => {
    const live = liveRef.current;
    if (!snapshot || !mountedRef.current || !live) return false;
    return (
      live.sessionToken === snapshot.sessionToken &&
      live.contextVersion === snapshot.contextVersion &&
      live.actor === snapshot.actor &&
      live.role === snapshot.role &&
      live.selectedZone === snapshot.timezone &&
      live.quietStart === snapshot.quietHoursStart &&
      live.quietEnd === snapshot.quietHoursEnd
    );
  }, []);

  const makeSnapshot = (category: PushCategory): ConfirmReceipt | null => {
    if (!actor) return null;
    return {
      sessionToken,
      contextVersion,
      actor,
      role,
      category,
      timezone: selectedZone,
      quietHoursStart: quietStart,
      quietHoursEnd: quietEnd,
    };
  };

  const handleConfirm = useCallback((snapshot: ConfirmReceipt | null) => {
    if (!isHandlerSnapshotValid(snapshot)) {
      return;
    }
    setConfirmations((prev) => ({ ...prev, [snapshot.category]: snapshot }));
    setPickerExpanded(false);
    setFormError('');
  }, [isHandlerSnapshotValid]);

  const reminderMutation = useMutation({
    mutationFn: ({ type, enabled }: { type: ReminderCategory; enabled: boolean; startToken: symbol; startActor: string; startRole: string }) =>
      reminderApi.set(type, enabled),
    onSuccess: (_data, vars) => {
      if (!mountedRef.current || liveRef.current?.sessionToken !== vars.startToken) return;
      void cache.invalidateQueries({ queryKey: ['reminder-preferences', vars.startActor, vars.startRole] });
    },
    onError: (error, vars) => {
      if (!mountedRef.current || liveRef.current?.sessionToken !== vars.startToken) return;
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        cache.removeQueries({ queryKey: ['reminder-preferences', vars.startActor, vars.startRole] });
      }
      setFormError(error instanceof Error ? error.message : 'Không thể lưu tùy chọn nhắc lịch.');
    },
  });

  const save = async (category: PushCategory, title: string, enabled: boolean, buttonSnapshot: ConfirmReceipt | null) => {
    if (!isHandlerSnapshotValid(buttonSnapshot) || buttonSnapshot.category !== category || activePushOpRef.current !== null) {
      return;
    }
    const current = query.data?.find((item) => item.eventType === category);
    const categoryConfirm = confirmations[category];
    const hasConfirmedForCategory = isHandlerSnapshotValid(categoryConfirm) && categoryConfirm.category === category;
    const effectiveTimezone = hasConfirmedForCategory
      ? categoryConfirm.timezone
      : (!enabled && !quietStart && !quietEnd && current?.timezone ? current.timezone : '');

    if (!effectiveTimezone || (enabled && !hasConfirmedForCategory)) {
      setFormError(`Hãy xác nhận khung giờ áp dụng riêng cho mục "${title}" trước khi bật.`);
      return;
    }
    if (
      (quietStart === '') !== (quietEnd === '') ||
      (quietStart && (!/^\d{2}:\d{2}$/.test(quietStart) || !/^\d{2}:\d{2}$/.test(quietEnd) || quietStart === quietEnd))
    ) {
      setFormError('Giờ yên tĩnh cần hai mốc HH:mm khác nhau hoặc để trống cả hai.');
      return;
    }

    const opId = Symbol('push-set');
    const startToken = buttonSnapshot.sessionToken;
    activePushOpRef.current = opId;
    setSavingPushOp(opId);
    setFormError('');

    const input: PushPreferenceInput = {
      enabled,
      timezone: effectiveTimezone,
      quietHoursStart: quietStart || null,
      quietHoursEnd: quietEnd || null,
    };

    try {
      await pushApi.set(category, input);
      if (!mountedRef.current || liveRef.current?.sessionToken !== startToken || activePushOpRef.current !== opId) {
        return;
      }
      setConfirmations((prev) => {
        const next = { ...prev };
        delete next[category];
        return next;
      });
      setFormError('');
      void cache.invalidateQueries({ queryKey });
    } catch (error) {
      if (!mountedRef.current || liveRef.current?.sessionToken !== startToken || activePushOpRef.current !== opId) {
        return;
      }
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        cache.removeQueries({ queryKey });
      }
      setFormError(error instanceof Error ? error.message : 'Không thể lưu tùy chọn.');
    } finally {
      if (mountedRef.current && liveRef.current?.sessionToken === startToken && activePushOpRef.current === opId) {
        activePushOpRef.current = null;
        setSavingPushOp(null);
      }
    }
  };

  const concealed = query.isError && query.error instanceof ApiError && [403, 404].includes(query.error.status);
  useEffect(() => {
    if (concealed) cache.removeQueries({ queryKey: ['push-preferences', actor, role] });
  }, [concealed, cache, actor, role]);
  const reminderConcealed = reminderQuery.isError && reminderQuery.error instanceof ApiError && [403, 404].includes(reminderQuery.error.status);
  useEffect(() => {
    if (reminderConcealed) cache.removeQueries({ queryKey: ['reminder-preferences', actor, role] });
  }, [reminderConcealed, cache, actor, role]);

  const targetCategoryEntry = pushCategories.find(([cat]) => cat === targetCategory) ?? pushCategories[0];
  const activeTargetCategory = (targetCategoryEntry?.[0] ?? 'CHAT') as PushCategory;
  const activeTargetTitle = targetCategoryEntry?.[1] ?? 'Tin nhắn';
  const targetConfirmation = confirmations[activeTargetCategory];
  const targetConfirmed = isRenderConfirmed(targetConfirmation, activeTargetCategory);
  const targetConfirmSnapshot = makeSnapshot(activeTargetCategory);
  const pushBusy = savingPushOp !== null;
  const reminderBusy = reminderMutation.isPending;

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView testID="push-settings" contentContainerStyle={styles.content}>
        <Text style={[styles.title, { color: theme.textPrimary }]}>Thông báo đẩy</Text>
        <CoachingButton label="Quay lại thông báo" secondary onPress={() => router.back()} />
        <Text style={[styles.body, { color: theme.textSecondary }]}>
          Mặc định tắt. Hộp thư trong ứng dụng vẫn hoạt động. Expo xác nhận ticket và nhà cung cấp; chúng không chứng minh thiết bị đã nhận hoặc bạn đã đọc.
        </Text>
        <CoachingCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Nhắc lịch trong ứng dụng</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Mặc định tắt. Bật riêng từng loại; bật thông báo đẩy không tự bật nhắc trong ứng dụng.
          </Text>
          {reminderQuery.isPending ? <CoachingState busy title="Đang tải nhắc lịch" message="Vui lòng chờ." /> : null}
          {reminderQuery.isError ? <CoachingState title="Không thể tải nhắc lịch" message="Vui lòng thử lại." onRetry={() => reminderQuery.refetch()} /> : null}
          {!reminderQuery.isPending && !reminderQuery.isError
            ? (['APPOINTMENT_REMINDER', 'WORKOUT_REMINDER'] as const).map((type) => {
                const enabled = reminderQuery.data?.find((item) => item.type === type)?.enabled ?? false;
                const title = type === 'APPOINTMENT_REMINDER' ? 'Lịch hẹn' : 'Buổi tập';
                return (
                  <View key={type}>
                    <Text style={[styles.body, { color: theme.textSecondary }]}>
                      {title}: {enabled ? 'Đang bật' : 'Đang tắt'}
                    </Text>
                    <CoachingButton
                      label={`${enabled ? 'Tắt' : 'Bật'} nhắc ${title.toLowerCase()} trong ứng dụng`}
                      busy={reminderBusy}
                      disabled={reminderBusy}
                      onPress={() => reminderMutation.mutate({ type, enabled: !enabled, startToken: sessionToken, startActor: actor, startRole: role })}
                    />
                  </View>
                );
              })
            : null}
        </CoachingCard>
        {query.isPending ? <CoachingState busy title="Đang tải tùy chọn" message="Vui lòng chờ." /> : null}
        {query.isError ? <CoachingState title="Không thể tải tùy chọn" message="Vui lòng thử lại." onRetry={() => query.refetch()} /> : null}
        {!query.isError ? (
          <View>
            <TimezoneContextPicker
              testIDPrefix="push-timezone"
              label="Khung giờ giờ yên tĩnh"
              value={selectedZone}
              expanded={pickerExpanded}
              onToggleExpanded={() => setPickerExpanded((v) => !v)}
              extraTimezones={[...(query.data?.map((item) => item.timezone) ?? [])]}
              onChange={(nextZone) => {
                setSelectedZone(nextZone);
                setPickerExpanded(false);
                invalidateContext();
              }}
            />
            {pushCategories.length > 1 ? (
              <View style={localStyles.categoryRow} accessibilityRole="radiogroup" accessibilityLabel="Chọn loại thông báo để xác nhận khung giờ">
                {pushCategories.map(([cat, title]) => {
                  const active = cat === activeTargetCategory;
                  return (
                    <Pressable
                      key={cat}
                      testID={`push-target-category-${cat}`}
                      accessibilityRole="radio"
                      accessibilityState={{ checked: active, selected: active }}
                      onPress={() => {
                        setTargetCategory(cat);
                        setFormError('');
                      }}
                      style={[
                        localStyles.categoryChip,
                        {
                          borderColor: active ? theme.primary : theme.borderStrong,
                          backgroundColor: active ? theme.brandSoft : theme.surface,
                          borderWidth: active ? 2 : 1,
                        },
                      ]}
                    >
                      <Text style={[localStyles.categoryChipLabel, { color: active ? theme.primary : theme.textPrimary }]}>
                        {active ? '✓ ' : ''}
                        {title}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            ) : null}
            <Text testID="push-timezone-display" style={[styles.label, { color: theme.textPrimary }]}>
              Trạng thái xác nhận cho {activeTargetTitle}:{' '}
              {targetConfirmed
                ? `Đã xác nhận ${formatTimezoneDisplay(targetConfirmation.timezone)}`
                : `Chưa xác nhận (${formatTimezoneDisplay(selectedZone)})`}
            </Text>
            <CoachingButton
              testID="push-confirm-timezone-button"
              label={
                targetConfirmed
                  ? `Đã xác nhận ${formatTimezoneDisplay(targetConfirmation.timezone)} cho ${activeTargetTitle}`
                  : `Xác nhận dùng ${formatTimezoneDisplay(selectedZone)} cho ${activeTargetTitle}`
              }
              secondary={targetConfirmed}
              onPress={() => handleConfirm(targetConfirmSnapshot)}
            />
            <TextInput
              accessibilityLabel="Giờ bắt đầu yên tĩnh HH:mm"
              placeholder="Bắt đầu HH:mm (có thể bỏ trống)"
              value={quietStart}
              onChangeText={(value) => {
                setQuietStart(value);
                invalidateContext();
              }}
              style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]}
            />
            <TextInput
              accessibilityLabel="Giờ kết thúc yên tĩnh HH:mm"
              placeholder="Kết thúc HH:mm (có thể bỏ trống)"
              value={quietEnd}
              onChangeText={(value) => {
                setQuietEnd(value);
                invalidateContext();
              }}
              style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]}
            />
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Nhắc lịch qua thông báo đẩy chỉ gửi sau giờ yên tĩnh nếu lịch chưa bắt đầu. Xác nhận riêng từng loại thông báo đẩy trước khi bật.
            </Text>
          </View>
        ) : null}
        {formError ? <CoachingState title="Chưa lưu được" message={formError} /> : null}
        {!query.isPending && !query.isError
          ? pushCategories.map(([category, title]) => {
              const current = query.data?.find((item) => item.eventType === category);
              const catConfirmation = confirmations[category];
              const catConfirmed = isRenderConfirmed(catConfirmation, category);
              const catConfirmSnapshot = makeSnapshot(category);
              const catActionSnapshot = makeSnapshot(category);
              return (
                <CoachingCard key={category}>
                  <Text style={[styles.h2, { color: theme.textPrimary }]}>{title}</Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    Thông báo đẩy: {current?.enabled ? 'Đang bật' : 'Đang tắt'}
                    {current?.timezone ? ` · ${formatTimezoneDisplay(current.timezone)}` : ''}
                  </Text>
                  <CoachingButton
                    testID={`push-confirm-category-button-${category}`}
                    label={
                      catConfirmed
                        ? `Đã xác nhận ${formatTimezoneDisplay(catConfirmation.timezone)} cho ${title}`
                        : `Xác nhận khung giờ cho ${title}`
                    }
                    secondary
                    onPress={() => handleConfirm(catConfirmSnapshot)}
                  />
                  <CoachingButton
                    label={current?.enabled ? `Tắt thông báo đẩy ${title}` : `Bật thông báo đẩy ${title}`}
                    busy={pushBusy}
                    disabled={pushBusy}
                    onPress={() => void save(category, title, !current?.enabled, catActionSnapshot)}
                  />
                </CoachingCard>
              );
            })
          : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const localStyles = StyleSheet.create({
  categoryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginVertical: spacing.xs,
  },
  categoryChip: {
    minHeight: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    justifyContent: 'center',
  },
  categoryChipLabel: {
    ...typography.bodySmall,
    fontFamily: fontFamily.semiBold,
  },
});
