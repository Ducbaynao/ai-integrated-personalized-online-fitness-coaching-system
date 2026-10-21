import { useLocalSearchParams } from 'expo-router';
import { AppointmentCreateScreen } from '@/features/appointments/AppointmentScreens';

export default function AppointmentRescheduleRoute() {
  const { appointmentId } = useLocalSearchParams<{ appointmentId: string }>();
  return <AppointmentCreateScreen studentId="" appointmentId={appointmentId ?? ''} />;
}
