import { useLocalSearchParams } from 'expo-router';
import { AppointmentRequestScreen } from '@/features/appointments/AppointmentScreens';

export default function AppointmentRequestRoute() {
  const { requestId } = useLocalSearchParams<{ requestId: string }>();
  return <AppointmentRequestScreen requestId={requestId ?? ''} />;
}
