import React, { useRef, useState } from 'react';
import { ScrollView, Text, TextInput, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { coachingQueryKeys } from './coachingQueries';
import { coachingErrorMessage } from './coachingMessages';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';

export function StudentLookupScreen() {
  const [email, setEmail] = useState('');
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const router = useRouter();
  const queryClient = useQueryClient();
  const invitationKey = useRef<string | null>(null);
  const lookup = useMutation({ mutationFn: () => coachingApi.lookupStudent(email) });
  const invite = useMutation({ mutationFn: ({ studentId, commandKey }: { studentId: string; commandKey: string }) => coachingApi.inviteStudent(studentId, commandKey), onSuccess: async (result) => {
    invitationKey.current = null;
    await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.relationships() });
    if (result.relationship) router.replace(`/coaching/${result.relationship.id}` as never);
  }});
  const changeEmail = (value: string) => { setEmail(value); invitationKey.current = null; lookup.reset(); invite.reset(); };
  const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  return <ScrollView testID="student-lookup-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Mời học viên</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Nhập chính xác email. Hệ thống không cung cấp danh bạ học viên.</Text>
    <Text style={[styles.label, { color: theme.textPrimary }]}>Email học viên</Text>
    <TextInput testID="student-email-input" accessibilityLabel="Email chính xác của học viên" keyboardType="email-address" autoCapitalize="none"
      value={email} onChangeText={changeEmail} placeholder="hocvien@example.com" placeholderTextColor={theme.textSecondary}
      style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    <CoachingButton testID="lookup-student-submit" label={lookup.isPending ? 'Đang kiểm tra' : 'Kiểm tra học viên'} busy={lookup.isPending} disabled={!valid || lookup.isPending || invite.isPending} onPress={() => lookup.mutate()} />
    {lookup.isError ? <CoachingState testID="student-lookup-error" title="Không thể xác nhận học viên" message={coachingErrorMessage(lookup.error)} /> : null}
    {invite.isError ? <CoachingState title="Chưa thể gửi lời mời" message={coachingErrorMessage(invite.error)} /> : null}
    {lookup.data ? <CoachingCard testID="student-lookup-result">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{lookup.data.displayName}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Đã tìm thấy người dùng khả dụng cho lời mời huấn luyện.</Text>
      <CoachingButton label={`Gửi lời mời đến ${lookup.data.displayName}`} busy={invite.isPending} disabled={invite.isPending} onPress={() => {
        const commandKey = invitationKey.current ?? createCommandKey();
        invitationKey.current = commandKey;
        invite.mutate({ studentId: lookup.data!.studentId, commandKey });
      }} />
    </CoachingCard> : null}
  </ScrollView>;
}
