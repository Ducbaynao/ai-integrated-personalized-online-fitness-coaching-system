import { useLocalSearchParams } from 'expo-router';
import { ChatLinkedResourceScreen } from '@/features/chat/ChatScreens';

export default function ChatLinkedResourceRoute() {
  const { messageId } = useLocalSearchParams<{ messageId: string }>();
  return <ChatLinkedResourceScreen messageId={messageId} />;
}
