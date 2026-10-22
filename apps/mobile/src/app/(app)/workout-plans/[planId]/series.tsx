import { useLocalSearchParams } from 'expo-router';
import { StudentRecurrenceCreateScreen } from '@/features/workout-recurrence/RecurrenceCreateScreen';
export default function Screen() {
  const { planId } = useLocalSearchParams<{ planId: string }>();
  return <StudentRecurrenceCreateScreen planId={planId ?? ''} />;
}
