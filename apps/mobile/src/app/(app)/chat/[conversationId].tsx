import { useLocalSearchParams } from 'expo-router';
import { ConversationScreen } from '@/features/chat/ChatScreens';
export default function ChatDetailRoute() {
  const { conversationId } = useLocalSearchParams<{ conversationId: string }>();
  return <ConversationScreen conversationId={conversationId} />;
}
