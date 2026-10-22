import { useLocalSearchParams } from 'expo-router';
import { TrainerRecurrenceCreateScreen } from '@/features/workout-recurrence/RecurrenceCreateScreen';
export default function Screen() {
  const { relationshipId } = useLocalSearchParams<{ relationshipId: string }>();
  return <TrainerRecurrenceCreateScreen relationshipId={relationshipId ?? ''} />;
}
