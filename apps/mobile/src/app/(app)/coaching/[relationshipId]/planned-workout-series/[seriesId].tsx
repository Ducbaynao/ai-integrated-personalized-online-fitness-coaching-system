import { useLocalSearchParams } from 'expo-router';
import { TrainerRecurrenceDetailScreen } from '@/features/workout-recurrence/RecurrenceDetailScreen';
export default function Screen() {
  const { relationshipId, seriesId } = useLocalSearchParams<{ relationshipId: string; seriesId: string }>();
  return <TrainerRecurrenceDetailScreen relationshipId={relationshipId ?? ''} seriesId={seriesId ?? ''} />;
}
