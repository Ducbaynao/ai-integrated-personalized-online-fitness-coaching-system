import { useLocalSearchParams } from 'expo-router';
import { StudentRecurrenceDetailScreen } from '@/features/workout-recurrence/RecurrenceDetailScreen';
export default function Screen() {
  const { seriesId } = useLocalSearchParams<{ seriesId: string }>();
  return <StudentRecurrenceDetailScreen seriesId={seriesId ?? ''} />;
}
