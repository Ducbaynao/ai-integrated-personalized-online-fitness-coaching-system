import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { StudentOnboardingScreen } from '@/features/student/StudentOnboardingScreen';

export default function StudentOnboardingRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // If user already has student profile, redirect to (app)
  if (user?.capabilities?.hasStudentProfile) {
    return <Redirect href="/(app)" />;
  }

  return <StudentOnboardingScreen />;
}
