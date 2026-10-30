import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { createCommandKey } from '@/services/coachingApi';
import { chatApi, ChatConversation, ChatMessage, ChatLinkedResource, SendChatBody, SendChatLinkedResource } from '@/services/chatApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { appointmentApi } from '@/services/appointmentApi';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { useChatUpdates } from './useChatUpdates';
import * as DocumentPicker from 'expo-document-picker';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { pushApi, PushDeliveryState } from '@/services/pushApi';

type PendingAttachment = { uri: string; name: string; mimeType: string; size: number; commandKey: string };
const attachmentTypes = ['image/jpeg', 'image/png', 'application/pdf'];

function discardPickedCopy(uri: string) {
  // DocumentPicker is configured to copy into its private cache. Never delete an external URI.
  if (!uri.startsWith('file://') || !uri.includes('/cache/DocumentPicker/')) return;
  try { new File(uri).delete(); } catch { /* Expo may revoke access to a picked cache file. */ }
}

const key = (actor: string, role: string, resource: string, id?: string) => ['chat', actor, role, resource, id];
const messageStatus: Record<ChatMessage['status'], string> = {
  SENT: 'Đã gửi', DELIVERED: 'Đã nhận', READ: 'Đã đọc',
};
function errorText(error: unknown) {
  if (error instanceof ApiError && [403, 404].includes(error.status)) return 'Không còn quyền xem nội dung này.';
  if (error instanceof ApiError && error.status === 0) return 'Mất kết nối. Vui lòng thử lại.';
  return 'Không thể tải dữ liệu. Vui lòng thử lại.';
}
function useActor() {
  const { user, activeCapability } = useAuth();
  return { actor: user?.id ?? '', role: activeCapability ?? '' };
}

export function ChatInboxScreen() {
  const { actor, role } = useActor();
  useChatUpdates(actor, role);
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const query = useInfiniteQuery({
    queryKey: key(actor, role, 'conversations'), enabled: !!actor,
    gcTime: 0,
    initialPageParam: 0, queryFn: ({ pageParam }) => chatApi.conversations(pageParam),
    getNextPageParam: (last) => last.hasMore ? last.page + 1 : undefined,
    refetchInterval: 15000,
  });
  const rows = query.data?.pages.flatMap(page => page.items) ?? [];
  const concealed = query.isError && query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const cache = useQueryClient();
  useEffect(() => { if (concealed) cache.removeQueries({ queryKey: ['chat', actor, role] }); }, [concealed, actor, role, cache]);
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="chat-inbox" style={styles.screen} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Tin nhắn</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch sử được kiểm tra quyền khi tải. Tin nhắn mới chỉ đến từ kỳ huấn luyện hiện hành.</Text>
    <CoachingButton label="Thông báo" secondary onPress={() => router.push('/notifications' as never)} />
    {query.isPending ? <CoachingState busy title="Đang tải hội thoại" message="Vui lòng chờ." /> : null}
    {query.isError ? <CoachingState title="Không thể tải hội thoại" message={errorText(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && rows.length === 0 ? <CoachingState title="Chưa có tin nhắn" message="Mở quan hệ huấn luyện hiện hành để gửi tin đầu tiên." /> : null}
    {!concealed && !query.isError && rows.map((row: ChatConversation) => <Pressable key={row.id} accessibilityRole="button"
      accessibilityLabel={`Mở hội thoại ngày ${new Date(row.updatedAt).toLocaleDateString('vi-VN')}`}
      onPress={() => router.push(`/chat/${row.id}` as never)}>
      <CoachingCard><Text style={[styles.h2, { color: theme.textPrimary }]}>Hội thoại huấn luyện</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Cập nhật {new Date(row.updatedAt).toLocaleString('vi-VN')}</Text></CoachingCard>
    </Pressable>)}
    {query.hasNextPage && !query.isError ? <CoachingButton label="Tải thêm" secondary busy={query.isFetchingNextPage}
      disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView></SafeAreaView>;
}

export function ConversationScreen({ conversationId, studentId }: { conversationId?: string; studentId?: string }) {
  const { actor, role } = useActor();
  useChatUpdates(actor, role, conversationId);
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<SendChatBody | null>(null);
  const [attachment, setAttachment] = useState<PendingAttachment | null>(null);
  const [attachmentError, setAttachmentError] = useState('');
  const [showLinks, setShowLinks] = useState(false);
  const [pendingLink, setPendingLink] = useState<SendChatLinkedResource | null>(null);
  const [downloadBusy, setDownloadBusy] = useState<string | null>(null);
  const deliveredOnDevice = useRef(new Set<string>());
  const detail = useQuery({ queryKey: key(actor, role, 'detail', conversationId),
    gcTime: 0,
    enabled: !!actor && !!conversationId, queryFn: () => chatApi.conversation(conversationId!), refetchInterval: 15000 });
  const messages = useInfiniteQuery({ queryKey: key(actor, role, 'messages', conversationId),
    gcTime: 0,
    enabled: !!actor && !!conversationId && !detail.isError,
    initialPageParam: 0, queryFn: ({ pageParam }) => chatApi.messages(conversationId!, pageParam),
    getNextPageParam: last => last.hasMore ? last.page + 1 : undefined, refetchInterval: 15000 });
  const targetStudent = conversationId ? detail.data?.studentId : studentId;
  const linkWindow = useMemo(() => {
    const from = new Date();
    from.setDate(from.getDate() - 7);
    const until = new Date();
    until.setDate(until.getDate() + 30);
    return { from: from.toISOString(), until: until.toISOString() };
  }, []);
  const plans = useQuery({ queryKey: key(actor, role, 'link-plans', targetStudent),
    enabled: showLinks && !!targetStudent, queryFn: () => workoutPlanApi.list(0, 20, targetStudent) });
  const workouts = useQuery({ queryKey: key(actor, role, 'link-workouts', targetStudent),
    enabled: showLinks && !!targetStudent, queryFn: () => role === 'TRAINER'
      ? workoutScheduleApi.listForTrainer(targetStudent!, linkWindow.from, linkWindow.until)
      : workoutScheduleApi.list(linkWindow.from, linkWindow.until) });
  const appointments = useQuery({ queryKey: key(actor, role, 'link-appointments', targetStudent),
    enabled: showLinks && !!targetStudent, queryFn: () => appointmentApi.appointments(linkWindow.from, linkWindow.until) });
  const sendLink = useMutation({ mutationFn: (body: SendChatLinkedResource) => chatApi.sendLinked(targetStudent!, body),
    onSuccess: async result => {
      setPendingLink(null); setShowLinks(false);
      await cache.invalidateQueries({ queryKey: ['chat', actor, role] });
      if (!conversationId) router.replace(`/chat/${result.conversationId}` as never);
    },
    onError: error => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) {
        setPendingLink(null); setShowLinks(false);
        cache.removeQueries({ queryKey: ['chat', actor, role] });
      }
    },
  });
  const chooseLink = (link: ChatLinkedResource) => {
    const body = { ...link, commandKey: createCommandKey() };
    setPendingLink(body);
    sendLink.mutate(body);
  };
  const send = useMutation({ mutationFn: (body: SendChatBody) => chatApi.send(targetStudent!, body),
    onSuccess: async result => {
      setPending(null); setDraft('');
      await cache.invalidateQueries({ queryKey: ['chat', actor, role] });
      if (!conversationId) router.replace(`/chat/${result.conversationId}` as never);
    },
    onError: error => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) {
        setPending(null);
        cache.removeQueries({ queryKey: ['chat', actor, role] });
      }
    },
  });
  const sendFile = useMutation({ mutationFn: (file: PendingAttachment) => chatApi.sendAttachment(targetStudent!, file, file.commandKey),
    onSuccess: async (result, file) => {
      discardPickedCopy(file.uri);
      setAttachment(null); setAttachmentError('');
      await cache.invalidateQueries({ queryKey: ['chat', actor, role] });
      if (!conversationId) router.replace(`/chat/${result.conversationId}` as never);
    },
    onError: (error, file) => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) {
        discardPickedCopy(file.uri);
        setAttachment(null);
        cache.removeQueries({ queryKey: ['chat', actor, role] });
      }
    },
  });
  const chooseAttachment = async () => {
    const picked = await DocumentPicker.getDocumentAsync({ type: attachmentTypes, copyToCacheDirectory: true });
    if (picked.canceled) return;
    const file = picked.assets[0];
    const mimeType = file?.mimeType === 'image/jpg' ? 'image/jpeg' : file?.mimeType;
    if (!file || !mimeType || !attachmentTypes.includes(mimeType) || !file.size || file.size > 10 * 1024 * 1024) {
      if (file) discardPickedCopy(file.uri);
      setAttachmentError('Chỉ chọn JPEG, PNG hoặc PDF tối đa 10 MiB.');
      return;
    }
    if (attachment) discardPickedCopy(attachment.uri);
    setAttachmentError('');
    setAttachment({ uri: file.uri, name: file.name, mimeType, size: file.size, commandKey: createCommandKey() });
  };
  const openAttachment = async (message: ChatMessage) => {
    if (!message.attachmentId) return;
    setDownloadBusy(message.id); setAttachmentError('');
    const extension = message.attachmentContentType === 'application/pdf' ? '.pdf'
      : message.attachmentContentType === 'image/png' ? '.png' : '.jpg';
    const temporary = new File(Paths.cache, `chat-${message.attachmentId}${extension}`);
    try {
      const bytes = await chatApi.attachmentContent(message.attachmentId);
      temporary.create({ overwrite: true });
      await temporary.write(bytes);
      if (await Sharing.isAvailableAsync()) await Sharing.shareAsync(temporary.uri, { mimeType: message.attachmentContentType ?? undefined });
      else setAttachmentError('Thiết bị chưa hỗ trợ mở tệp.');
    } catch (error) {
      if (error instanceof ApiError && [403, 404].includes(error.status)) cache.removeQueries({ queryKey: ['chat', actor, role] });
      setAttachmentError(errorText(error));
    } finally {
      try { temporary.delete(); } catch { /* Ephemeral cache cleanup. */ }
      setDownloadBusy(null);
    }
  };
  const receipt = useMutation({ mutationFn: ({ id, kind }: { id: string; kind: 'DELIVERED' | 'READ' }) => chatApi.receipt(id, kind),
    onSuccess: () => cache.invalidateQueries({ queryKey: key(actor, role, 'messages', conversationId) }),
  });
  // Receipt is explicit: opening the screen does not mark messages READ.
  const visibleMessages = useMemo(() => messages.data?.pages.flatMap(page => page.items) ?? [], [messages.data]);
  useEffect(() => {
    if (!actor || detail.isError || messages.isError) return;
    for (const message of visibleMessages) {
      if (message.senderId === actor || message.deliveredAt || deliveredOnDevice.current.has(message.id)) continue;
      deliveredOnDevice.current.add(message.id);
      void chatApi.receipt(message.id, 'DELIVERED').then(() => {
        void cache.invalidateQueries({ queryKey: key(actor, role, 'messages', conversationId) });
      }).catch(error => {
        deliveredOnDevice.current.delete(message.id);
        if (error instanceof ApiError && [403, 404].includes(error.status))
          cache.removeQueries({ queryKey: ['chat', actor, role] });
      });
    }
  }, [actor, role, conversationId, visibleMessages, detail.isError, messages.isError, cache]);
  const concealed = detail.isError && detail.error instanceof ApiError && [403, 404].includes(detail.error.status);
  useEffect(() => { if (concealed) cache.removeQueries({ queryKey: ['chat', actor, role] }); }, [concealed, actor, role, cache]);
  const submit = () => {
    if (send.isPending || !targetStudent) return;
    const body = pending ?? { body: draft.trim(), commandKey: createCommandKey() };
    if (!body.body || body.body.length > 4000) return;
    setPending(body); send.mutate(body);
  };
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="chat-conversation" style={styles.screen} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Hội thoại</Text>
    <CoachingButton label="Quay lại tin nhắn" secondary onPress={() => router.push('/chat' as never)} />
    {detail.isPending && conversationId ? <CoachingState busy title="Đang tải hội thoại" message="Vui lòng chờ." /> : null}
    {detail.isError ? <CoachingState title="Không thể mở hội thoại" message={errorText(detail.error)} onRetry={() => detail.refetch()} /> : null}
    {messages.isPending && conversationId ? <CoachingState busy title="Đang tải tin nhắn" message="Vui lòng chờ." /> : null}
    {messages.isError ? <CoachingState title="Không thể tải tin nhắn" message={errorText(messages.error)} onRetry={() => messages.refetch()} /> : null}
    {!conversationId ? <CoachingState title="Tin nhắn đầu tiên" message="Hội thoại chỉ được tạo sau khi gửi tin thành công trong kỳ huấn luyện hiện hành." /> : null}
    {conversationId && detail.data && !detail.data.sendAllowed ? <CoachingState title="Hội thoại chỉ đọc" message="Kỳ huấn luyện này không còn cho phép gửi tin. Lịch sử của bạn vẫn được giữ nguyên." /> : null}
    {!concealed && !messages.isError && visibleMessages.map(message => <CoachingCard key={message.id}>
      <Text style={[styles.body, { color: theme.textPrimary }]}>{message.body}</Text>
      {message.attachmentId ? <CoachingButton label={`Mở tệp ${message.attachmentName ?? 'đính kèm'}`}
        secondary busy={downloadBusy === message.id} disabled={!!downloadBusy}
        onPress={() => { void openAttachment(message); }} /> : null}
      {message.linkedResourceType ? message.linkedResourceAvailable
        ? <CoachingButton label="Mở tài nguyên được chia sẻ" secondary
          onPress={() => router.push(`/chat/linked/${message.id}` as never)} />
        : <Text style={[styles.body, { color: theme.textSecondary }]}>Tài nguyên không còn khả dụng.</Text> : null}
      <Text style={[styles.label, { color: theme.textSecondary }]}>{message.senderId === actor ? 'Bạn' : 'Đối phương'} · {new Date(message.sentAt).toLocaleString('vi-VN')} · {messageStatus[message.status]}</Text>
      {message.senderId !== actor && !message.readAt ? <CoachingButton label="Đánh dấu đã đọc" secondary onPress={() => receipt.mutate({ id: message.id, kind: 'READ' })} /> : null}
    </CoachingCard>)}
    {messages.hasNextPage && !messages.isError ? <CoachingButton label="Tải tin cũ" secondary onPress={() => messages.fetchNextPage()} /> : null}
    {send.isError ? <CoachingState title="Chưa gửi được" message={errorText(send.error)} /> : null}
    {attachmentError ? <CoachingState title="Tệp đính kèm" message={attachmentError} /> : null}
    {sendFile.isError ? <CoachingState title="Chưa gửi được tệp" message={errorText(sendFile.error)} /> : null}
    {sendLink.isError ? <CoachingState title="Chưa gửi được liên kết" message={errorText(sendLink.error)}
      onRetry={pendingLink ? () => sendLink.mutate(pendingLink) : undefined} /> : null}
    {!concealed && (!conversationId || detail.data?.sendAllowed) ? <View>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Tin nhắn văn bản</Text>
      <TextInput testID="chat-draft" accessibilityLabel="Nội dung tin nhắn" value={draft} onChangeText={setDraft}
        editable={!pending} multiline maxLength={4000} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
      <CoachingButton label={pending && send.isError ? 'Thử gửi lại' : 'Gửi tin nhắn'} busy={send.isPending}
        disabled={send.isPending || (!pending && !draft.trim())} onPress={submit} />
      {pending && send.isError ? <CoachingButton label="Sửa tin nhắn" secondary onPress={() => setPending(null)} /> : null}
      <CoachingButton label="Chọn JPEG, PNG hoặc PDF" secondary onPress={() => { void chooseAttachment(); }} />
      {attachment ? <View>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{attachment.name} · {Math.ceil(attachment.size / 1024)} KiB</Text>
        <CoachingButton label={sendFile.isError ? 'Thử gửi lại tệp' : 'Gửi tệp đính kèm'}
          busy={sendFile.isPending} disabled={sendFile.isPending}
          onPress={() => sendFile.mutate(attachment)} />
        <CoachingButton label="Bỏ tệp đã chọn" secondary onPress={() => { discardPickedCopy(attachment.uri); setAttachment(null); setAttachmentError(''); }} />
      </View> : null}
      <CoachingButton label={showLinks ? 'Đóng danh sách tài nguyên' : 'Chia sẻ tài nguyên'} secondary
        onPress={() => setShowLinks(value => !value)} />
      {showLinks ? <View>
        <Text style={[styles.label, { color: theme.textPrimary }]}>Chỉ chọn tài nguyên hai bên được xem trong kỳ huấn luyện hiện hành.</Text>
        {plans.isPending || workouts.isPending || appointments.isPending
          ? <CoachingState busy title="Đang tải tài nguyên" message="Vui lòng chờ." /> : null}
        {plans.isError || workouts.isError || appointments.isError
          ? <CoachingState title="Không thể tải tài nguyên" message="Kiểm tra quyền và thử lại."
            onRetry={() => { void plans.refetch(); void workouts.refetch(); void appointments.refetch(); }} /> : null}
        {!plans.isError && plans.data?.items.filter(plan => plan.currentVersionId && plan.currentVersionLockedAt).map(plan =>
          <CoachingButton key={plan.id} label={`Gửi phiên bản kế hoạch: ${plan.name}`} secondary
            disabled={sendLink.isPending} onPress={() => chooseLink({ type: 'WORKOUT_PLAN_VERSION',
              resourceId: plan.currentVersionId!, parentId: plan.id })} />)}
        {!workouts.isError && workouts.data?.items.map(workout =>
          <CoachingButton key={workout.occurrenceId} label={`Gửi lịch tập: ${new Date(workout.plannedStartAt).toLocaleString('vi-VN')}`}
            secondary disabled={sendLink.isPending} onPress={() => chooseLink({ type: 'PLANNED_WORKOUT',
              resourceId: workout.occurrenceId, parentId: null })} />)}
        {!appointments.isError && appointments.data?.items.filter(item => item.studentId === targetStudent).map(item =>
          <CoachingButton key={item.appointmentId} label={`Gửi lịch hẹn: ${new Date(item.startAt).toLocaleString('vi-VN')}`}
            secondary disabled={sendLink.isPending} onPress={() => chooseLink({ type: 'APPOINTMENT',
              resourceId: item.appointmentId, parentId: null })} />)}
        {plans.data?.items.length === 0 && workouts.data?.items.length === 0 && appointments.data?.items.length === 0
          ? <CoachingState title="Chưa có tài nguyên" message="Không tìm thấy tài nguyên trong phạm vi đang xem." /> : null}
      </View> : null}
    </View> : null}
  </ScrollView></SafeAreaView>;
}

export function ChatLinkedResourceScreen({ messageId }: { messageId: string }) {
  const { actor, role } = useActor();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const router = useRouter();
  const link = useQuery({ queryKey: key(actor, role, 'linked-resource', messageId), gcTime: 0,
    enabled: !!actor && !!messageId, queryFn: () => chatApi.openLinked(messageId), refetchOnMount: 'always' });
  const source = useQuery({ queryKey: key(actor, role, 'linked-source', messageId), gcTime: 0,
    enabled: !!link.data && !link.isError,
    queryFn: async () => {
      const item = link.data!;
      if (item.type === 'WORKOUT_PLAN_VERSION') {
        if (!item.parentId) throw new Error('Missing plan ID');
        const plan = await workoutPlanApi.detail(item.parentId);
        const version = await workoutPlanApi.version(item.parentId, item.resourceId);
        return { title: plan.plan.name, summary: `Phiên bản ${version.version.versionNumber} · ${version.sessions.length} buổi tập` };
      }
      if (item.type === 'PLANNED_WORKOUT') {
        const workout = await workoutScheduleApi.detail(item.resourceId);
        return { title: 'Lịch tập', summary: new Date(workout.plannedStartAt).toLocaleString('vi-VN') };
      }
      const appointment = await appointmentApi.detail(item.resourceId);
      return { title: 'Lịch hẹn huấn luyện', summary: new Date(appointment.startAt).toLocaleString('vi-VN') };
    },
  });
  const denied = [link.error, source.error].some(error => error instanceof ApiError && [403, 404].includes(error.status));
  useEffect(() => { if (denied) cache.removeQueries({ queryKey: ['chat', actor, role] }); }, [denied, cache, actor, role]);
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
    <ScrollView testID="chat-linked-resource" contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: theme.textPrimary }]}>Tài nguyên được chia sẻ</Text>
      <CoachingButton label="Quay lại" secondary onPress={() => router.back()} />
      {link.isPending || source.isPending && !link.isError ? <CoachingState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." /> : null}
      {denied ? <CoachingState title="Không còn quyền xem" message="Tài nguyên không còn khả dụng trong kỳ huấn luyện hiện hành." /> : null}
      {!denied && (link.isError || source.isError) ? <CoachingState title="Không thể mở tài nguyên" message="Kiểm tra kết nối và thử lại."
        onRetry={() => { void link.refetch(); void source.refetch(); }} /> : null}
      {!denied && !link.isError && !source.isError && source.data ? <CoachingCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{source.data.title}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{source.data.summary}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu hiện hành; quyền được kiểm tra mỗi lần mở.</Text>
      </CoachingCard> : null}
    </ScrollView>
  </SafeAreaView>;
}

export function NotificationsScreen() {
  const { actor, role } = useActor();
  useChatUpdates(actor, role);
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [delivery, setDelivery] = useState<{ actor: string; role: string; id: string; items: PushDeliveryState[] } | null>(null);
  const [deliveryFailure, setDeliveryFailure] = useState<{ actor: string; role: string; message: string } | null>(null);
  const query = useInfiniteQuery({ queryKey: key(actor, role, 'notifications'), enabled: !!actor,
    gcTime: 0,
    initialPageParam: 0, queryFn: ({ pageParam }) => chatApi.notifications(pageParam),
    getNextPageParam: last => last.hasMore ? last.page + 1 : undefined, refetchInterval: 15000 });
  const read = useMutation({ mutationFn: chatApi.readNotification,
    onSuccess: () => cache.invalidateQueries({ queryKey: key(actor, role, 'notifications') }),
    onError: error => {
      if (error instanceof ApiError && [403, 404].includes(error.status)) cache.removeQueries({ queryKey: ['chat', actor, role] });
    },
  });
  const rows = query.data?.pages.flatMap(page => page.items) ?? [];
  const concealed = query.isError && query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const visibleDelivery = !concealed && delivery?.actor === actor && delivery.role === role ? delivery : null;
  const deliveryError = !concealed && deliveryFailure?.actor === actor && deliveryFailure.role === role ? deliveryFailure.message : '';
  useEffect(() => { if (concealed) {
    cache.removeQueries({ queryKey: ['chat', actor, role] });
    cache.removeQueries({ queryKey: ['push-preferences', actor, role] });
  } }, [concealed, actor, role, cache]);
  const showDelivery = async (id: string) => {
    setDeliveryFailure(null);
    try { setDelivery({ actor, role, id, items: await pushApi.deliveries(id) }); }
    catch (error) {
      setDelivery(null);
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        cache.removeQueries({ queryKey: ['chat', actor, role] });
        cache.removeQueries({ queryKey: ['push-preferences', actor, role] });
      }
      setDeliveryFailure({ actor, role, message: errorText(error) });
    }
  };
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="chat-notifications" style={styles.screen} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Thông báo trong ứng dụng</Text>
    <CoachingButton label="Mở tin nhắn" secondary onPress={() => router.push('/chat' as never)} />
    <CoachingButton label="Tùy chọn thông báo đẩy" secondary onPress={() => router.push('/notifications/push' as never)} />
    {query.isPending ? <CoachingState busy title="Đang tải thông báo" message="Vui lòng chờ." /> : null}
    {query.isError ? <CoachingState title="Không thể tải thông báo" message={errorText(query.error)} onRetry={() => query.refetch()} /> : null}
    {deliveryError ? <CoachingState title="Không thể xem trạng thái push" message={deliveryError} /> : null}
    {!query.isPending && !query.isError && rows.length === 0 ? <CoachingState title="Chưa có thông báo" message="Tin nhắn và cập nhật lịch hẹn sẽ xuất hiện ở đây." /> : null}
    {!query.isError && rows.map(item => <CoachingCard key={item.id}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{item.title}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{item.body}</Text>
      <Text style={[styles.label, { color: theme.textSecondary }]}>{new Date(item.createdAt).toLocaleString('vi-VN')}</Text>
      <CoachingButton label="Xem trạng thái push" secondary onPress={() => { void showDelivery(item.id); }} />
      {visibleDelivery?.id === item.id ? <Text style={[styles.body, { color: theme.textSecondary }]}>
        {visibleDelivery.items.length ? visibleDelivery.items.map(state => ({
          QUEUED: 'Đang chờ', TICKET_ACCEPTED: 'Expo đã nhận', PROVIDER_ACCEPTED: 'Nhà cung cấp đã nhận',
          DEVICE_ACKNOWLEDGED: 'Ứng dụng trên thiết bị đã xác nhận', FAILED: 'Gửi thất bại', SKIPPED: 'Không gửi do quyền/tùy chọn',
        }[state.status])).join(', ') : 'Không có thiết bị được bật push khi sự kiện xảy ra.'}
      </Text> : null}
      {item.relatedEntityType === 'APPOINTMENT_REQUEST' && item.relatedEntityId
        ? <CoachingButton label="Xem yêu cầu lịch hẹn" secondary onPress={() => router.push(`/appointments/requests/${item.relatedEntityId}` as never)} /> : null}
      {item.relatedEntityType === 'APPOINTMENT' && item.relatedEntityId
        ? <CoachingButton label="Xem lịch hẹn" secondary onPress={() => router.push(`/appointments/${item.relatedEntityId}` as never)} /> : null}
      {role === 'TRAINER' && item.relatedEntityType === 'PLANNED_WORKOUT' && item.relatedEntityId
        ? <CoachingButton label="Mở không gian Học viên để xem buổi tập đã hủy" secondary onPress={() => router.push('/coaching' as never)} /> : null}
      {role === 'STUDENT' && item.relatedEntityType === 'WORKOUT_SCHEDULE_PROPOSAL' && item.relatedEntityId
        ? <CoachingButton label="Xem đề xuất lịch tập" secondary onPress={() => router.push(`/workout-schedule-proposals/${item.relatedEntityId}` as never)} /> : null}
      {role === 'STUDENT' && item.relatedEntityType === 'WORKOUT_CHANGE_PROPOSAL' && item.relatedEntityId
        ? <CoachingButton label="Xem đề xuất đổi lịch tập" secondary onPress={() => router.push(`/workout-change-proposals/${item.relatedEntityId}` as never)} /> : null}
      {role === 'STUDENT' && item.relatedEntityType === 'NUTRITION_STRATEGY_PROPOSAL'
        ? <CoachingButton label="Xem đề xuất dinh dưỡng" secondary onPress={() => router.push('/nutrition/strategy' as never)} /> : null}
      {role === 'STUDENT' && item.relatedEntityType === 'FITNESS_GOAL_PROPOSAL' && item.relatedEntityId
        ? <CoachingButton label="Xem đề xuất mục tiêu" secondary onPress={() => router.push(`/goal-proposals/${item.relatedEntityId}` as never)} /> : null}
      {item.relatedEntityType === 'COACHING_REQUEST' && item.relatedEntityId
        ? <CoachingButton label="Xem yêu cầu huấn luyện" secondary onPress={() => router.push(`/coaching/${item.relatedEntityId}` as never)} /> : null}
      {item.relatedEntityType === 'COACHING_RESUME_REQUEST'
        ? <CoachingButton label="Xem quan hệ huấn luyện" secondary onPress={() => router.push('/coaching' as never)} /> : null}
      {['WORKOUT_ATTENTION_SIGNAL', 'REVIEW_ATTENTION_SIGNAL', 'COACHING_REVIEW_TASK']
        .includes(item.relatedEntityType ?? '')
        ? <CoachingButton label={role === 'STUDENT' ? 'Xem tín hiệu và đánh giá' : 'Mở học viên để xem tín hiệu và đánh giá'}
          secondary onPress={() => router.push(role === 'STUDENT' ? '/attention' as never : '/coaching' as never)} /> : null}
      {!item.readAt ? <CoachingButton label="Đánh dấu thông báo đã đọc" secondary onPress={() => read.mutate(item.id)} /> : null}
    </CoachingCard>)}
    {query.hasNextPage && !query.isError ? <CoachingButton label="Tải thêm" secondary onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView></SafeAreaView>;
}
