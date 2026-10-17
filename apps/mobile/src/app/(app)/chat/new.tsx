import { useLocalSearchParams } from 'expo-router';
import { ConversationScreen } from '@/features/chat/ChatScreens';
export default function NewChatRoute() {
  const { studentId } = useLocalSearchParams<{ studentId: string }>();
  return <ConversationScreen studentId={studentId} />;
}
