import { useLocalSearchParams } from 'expo-router';
import { AppointmentCreateScreen } from '@/features/appointments/AppointmentScreens';

export default function AppointmentCreateRoute() {
  const { studentId } = useLocalSearchParams<{ studentId: string }>();
  return <AppointmentCreateScreen studentId={studentId ?? ''} />;
}
