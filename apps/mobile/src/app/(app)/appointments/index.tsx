import { useLocalSearchParams } from 'expo-router';
import { AppointmentHomeScreen } from '@/features/appointments/AppointmentScreens';

export default function AppointmentHomeRoute() {
  const { studentId } = useLocalSearchParams<{ studentId?: string }>();
  return <AppointmentHomeScreen studentId={studentId} />;
}
