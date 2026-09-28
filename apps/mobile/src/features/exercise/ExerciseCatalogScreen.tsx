import React from 'react';
import { Href, useRouter } from 'expo-router';
import { ExerciseCatalogContent } from './components/ExerciseCatalogContent';
import { ExerciseListItem } from './components/ExerciseListItem';

export function ExerciseCatalogScreen() {
  const router = useRouter();

  return (
    <ExerciseCatalogContent
      title="Thư viện bài tập"
      subtitle="Tìm bài tập phù hợp theo nhóm cơ, thiết bị và độ khó."
      backLabel="Quay lại"
      onBack={() => router.back()}
      renderExercise={(exercise, isDark) => (
        <ExerciseListItem
          exercise={exercise}
          isDark={isDark}
          onPress={() =>
            router.push(
              {
                pathname: '/(app)/exercises/[exerciseId]',
                params: { exerciseId: exercise.id },
              } as unknown as Href
            )
          }
        />
      )}
    />
  );
}
