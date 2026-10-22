import { useLocalSearchParams } from 'expo-router';
import { TrainerRecurrenceListScreen } from '@/features/workout-recurrence/RecurrenceListScreen';
export default function Screen() {
  const { relationshipId } = useLocalSearchParams<{ relationshipId: string }>();
  return <TrainerRecurrenceListScreen relationshipId={relationshipId ?? ''} />;
}
