import { useLocalSearchParams } from 'expo-router';
import { AppointmentDetailScreen } from '@/features/appointments/AppointmentScreens';

export default function AppointmentDetailRoute() {
  const { appointmentId } = useLocalSearchParams<{ appointmentId: string }>();
  return <AppointmentDetailScreen appointmentId={appointmentId ?? ''} />;
}
