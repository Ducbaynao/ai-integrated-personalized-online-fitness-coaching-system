import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { createCommandKey } from '@/services/coachingApi';
import { chatApi, ChatConversation, ChatMessage, SendChatBody } from '@/services/chatApi';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { useChatUpdates } from './useChatUpdates';

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
  return <ScrollView testID="chat-inbox" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
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
  </ScrollView>;
}

export function ConversationScreen({ conversationId, studentId }: { conversationId?: string; studentId?: string }) {
  const { actor, role } = useActor();
  useChatUpdates(actor, role, conversationId);
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<SendChatBody | null>(null);
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
  return <ScrollView testID="chat-conversation" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
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
      <Text style={[styles.label, { color: theme.textSecondary }]}>{message.senderId === actor ? 'Bạn' : 'Đối phương'} · {new Date(message.sentAt).toLocaleString('vi-VN')} · {messageStatus[message.status]}</Text>
      {message.senderId !== actor && !message.readAt ? <CoachingButton label="Đánh dấu đã đọc" secondary onPress={() => receipt.mutate({ id: message.id, kind: 'READ' })} /> : null}
    </CoachingCard>)}
    {messages.hasNextPage && !messages.isError ? <CoachingButton label="Tải tin cũ" secondary onPress={() => messages.fetchNextPage()} /> : null}
    {send.isError ? <CoachingState title="Chưa gửi được" message={errorText(send.error)} /> : null}
    {!concealed && (!conversationId || detail.data?.sendAllowed) ? <View>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Tin nhắn văn bản</Text>
      <TextInput testID="chat-draft" accessibilityLabel="Nội dung tin nhắn" value={draft} onChangeText={setDraft}
        editable={!pending} multiline maxLength={4000} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
      <CoachingButton label={pending && send.isError ? 'Thử gửi lại' : 'Gửi tin nhắn'} busy={send.isPending}
        disabled={send.isPending || (!pending && !draft.trim())} onPress={submit} />
      {pending && send.isError ? <CoachingButton label="Sửa tin nhắn" secondary onPress={() => setPending(null)} /> : null}
    </View> : null}
  </ScrollView>;
}

export function NotificationsScreen() {
  const { actor, role } = useActor();
  useChatUpdates(actor, role);
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
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
  useEffect(() => { if (concealed) cache.removeQueries({ queryKey: ['chat', actor, role] }); }, [concealed, actor, role, cache]);
  return <ScrollView testID="chat-notifications" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Thông báo trong ứng dụng</Text>
    <CoachingButton label="Mở tin nhắn" secondary onPress={() => router.push('/chat' as never)} />
    {query.isPending ? <CoachingState busy title="Đang tải thông báo" message="Vui lòng chờ." /> : null}
    {query.isError ? <CoachingState title="Không thể tải thông báo" message={errorText(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && rows.length === 0 ? <CoachingState title="Chưa có thông báo" message="Tin nhắn mới sẽ xuất hiện ở đây." /> : null}
    {!query.isError && rows.map(item => <CoachingCard key={item.id}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{item.title}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{item.body}</Text>
      <Text style={[styles.label, { color: theme.textSecondary }]}>{new Date(item.createdAt).toLocaleString('vi-VN')}</Text>
      {!item.readAt ? <CoachingButton label="Đánh dấu thông báo đã đọc" secondary onPress={() => read.mutate(item.id)} /> : null}
    </CoachingCard>)}
    {query.hasNextPage && !query.isError ? <CoachingButton label="Tải thêm" secondary onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
