import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessStudentProfile } from '@/features/auth/routeGuard';
import { StudentProfileScreen } from '@/features/student/StudentProfileScreen';

export default function ProfileRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // Deep-link guard: user must have student profile to access student profile screen
  if (!canAccessStudentProfile(user)) {
    return <Redirect href="/(app)" />;
  }

  return <StudentProfileScreen />;
}
