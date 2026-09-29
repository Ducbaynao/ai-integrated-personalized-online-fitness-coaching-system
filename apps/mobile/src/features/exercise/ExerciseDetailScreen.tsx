import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { normalizeExerciseId } from '@/services/exerciseApi';
import { ExerciseDetail, ExerciseEquipment, ExerciseMuscle } from '@/types/exercise';
import { useExerciseDetail } from './exerciseQueries';
import {
  EXERCISE_DIFFICULTY_LABELS,
  EXERCISE_EQUIPMENT_REQUIREMENT_LABELS,
  EXERCISE_GUIDANCE_TYPE_LABELS,
  EXERCISE_MUSCLE_INVOLVEMENT_LABELS,
  getExerciseDetailErrorCopy,
} from './exerciseMessages';

interface ExerciseDetailScreenProps {
  exerciseId: unknown;
}

type ThemeColors = ReturnType<typeof getSemanticColors>;

function uniqueMuscles(detail: ExerciseDetail): ExerciseMuscle[] {
  const result = new Map<string, ExerciseMuscle>();
  detail.variations.forEach((variation) => {
    variation.muscles.forEach((muscle) => {
      result.set(`${muscle.code}:${muscle.involvement}`, muscle);
    });
  });
  return [...result.values()];
}

function uniqueEquipment(detail: ExerciseDetail): ExerciseEquipment[] {
  const result = new Map<string, ExerciseEquipment>();
  detail.variations.forEach((variation) => {
    variation.equipment.forEach((item) => {
      result.set(`${item.code}:${item.requirement}`, item);
    });
  });
  return [...result.values()];
}

function DetailHeader({ colors, onBack }: { colors: ThemeColors; onBack: () => void }) {
  return (
    <View style={styles.header}>
      <Pressable
        testID="exercise-detail-back"
        accessibilityRole="button"
        accessibilityLabel="Quay lại thư viện bài tập"
        accessibilityHint="Trở về màn hình trước"
        onPress={onBack}
        style={styles.backButton}>
        <Text style={[styles.backText, { color: colors.primary }]}>‹ Quay lại</Text>
      </Pressable>
      <Text accessibilityRole="header" style={[styles.screenTitle, { color: colors.textPrimary }]}>
        Chi tiết bài tập
      </Text>
    </View>
  );
}

function ExerciseDetailSkeleton({ colors }: { colors: ThemeColors }) {
  return (
    <View
      testID="exercise-detail-loading"
      accessibilityRole="progressbar"
      accessibilityLabel="Đang tải chi tiết bài tập"
      style={styles.skeleton}>
      <ActivityIndicator color={colors.primary} />
      <View style={[styles.skeletonHero, { backgroundColor: colors.surfaceSubtle }]} />
      {[0, 1, 2].map((item) => (
        <View key={item} style={[styles.skeletonLine, { backgroundColor: colors.surfaceSubtle }]} />
      ))}
    </View>
  );
}

function DetailState({
  colors,
  testID,
  title,
  message,
  onRetry,
}: {
  colors: ThemeColors;
  testID: string;
  title: string;
  message: string;
  onRetry?: () => void;
}) {
  return (
    <View testID={testID} accessibilityRole="alert" style={styles.state}>
      <Text accessibilityRole="header" style={[styles.stateTitle, { color: colors.textPrimary }]}>
        {title}
      </Text>
      <Text style={[styles.stateBody, { color: colors.textSecondary }]}>{message}</Text>
      {onRetry && (
        <Pressable
          testID="retry-exercise-detail"
          accessibilityRole="button"
          accessibilityLabel="Thử tải lại chi tiết bài tập"
          accessibilityHint="Gửi lại yêu cầu tải bài tập"
          onPress={onRetry}
          style={[styles.primaryButton, { backgroundColor: colors.primary }]}>
          <Text style={[styles.buttonText, { color: colors.textOnPrimary }]}>Thử lại</Text>
        </Pressable>
      )}
    </View>
  );
}

function Section({
  title,
  children,
  colors,
  testID,
}: {
  title: string;
  children: React.ReactNode;
  colors: ThemeColors;
  testID?: string;
}) {
  return (
    <View testID={testID} style={[styles.section, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.sectionTitle, { color: colors.textPrimary }]}>
        {title}
      </Text>
      {children}
    </View>
  );
}

export function ExerciseDetailScreen({ exerciseId }: ExerciseDetailScreenProps) {
  const router = useRouter();
  const colors = getSemanticColors(useColorScheme() === 'dark');
  const validExerciseId = normalizeExerciseId(exerciseId);
  const detailQuery = useExerciseDetail(validExerciseId);
  const detail = detailQuery.data;
  const refetch = detailQuery.refetch;
  const detailErrorCopy = detailQuery.isError
    ? getExerciseDetailErrorCopy(detailQuery.error)
    : null;
  const isTransientDetailError =
    detailErrorCopy?.kind === 'network' || detailErrorCopy?.kind === 'server';
  const isTerminalDetailError = detailQuery.isError && !isTransientDetailError;
  const canRenderStaleDetail = Boolean(
    detail && (!detailQuery.isError || isTransientDetailError)
  );

  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
      return;
    }
    router.replace('/(app)/exercises');
  };

  const shell = (content: React.ReactNode) => (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
      <DetailHeader colors={colors} onBack={handleBack} />
      {content}
    </SafeAreaView>
  );

  if (!validExerciseId) {
    return shell(
      <DetailState
        colors={colors}
        testID="exercise-detail-invalid-route"
        title="Đường dẫn bài tập không hợp lệ"
        message="Vui lòng quay lại thư viện bài tập và thử lại."
      />
    );
  }

  if (isTerminalDetailError && detailErrorCopy) {
    return shell(
      <DetailState
        colors={colors}
        testID={`exercise-detail-${detailErrorCopy.kind}`}
        title={detailErrorCopy.title}
        message={detailErrorCopy.message}
      />
    );
  }

  if (detailQuery.isPending) {
    return shell(<ExerciseDetailSkeleton colors={colors} />);
  }

  if (detailQuery.isError && !canRenderStaleDetail && detailErrorCopy) {
    return shell(
      <DetailState
        colors={colors}
        testID={`exercise-detail-${detailErrorCopy.kind}`}
        title={detailErrorCopy.title}
        message={detailErrorCopy.message}
        onRetry={() => refetch()}
      />
    );
  }

  if (!detail || !canRenderStaleDetail) {
    return shell(
      <DetailState
        colors={colors}
        testID="exercise-detail-generic"
        title="Không thể tải chi tiết bài tập"
        message="Đã xảy ra lỗi. Vui lòng thử lại."
        onRetry={() => refetch()}
      />
    );
  }

  const muscles = uniqueMuscles(detail);
  const equipment = uniqueEquipment(detail);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
      <DetailHeader colors={colors} onBack={handleBack} />
      <ScrollView
        testID="exercise-detail-scroll"
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={detailQuery.isRefetching}
            onRefresh={() => refetch()}
            tintColor={colors.primary}
          />
        }>
        {detailQuery.isRefetchError && isTransientDetailError && (
          <View
            testID="exercise-detail-background-error"
            accessibilityRole="alert"
            style={[styles.warning, { backgroundColor: colors.warningSurface }]}>
            <Text style={[styles.warningText, { color: colors.warningText }]}>
              Không thể cập nhật dữ liệu mới. Chi tiết gần nhất vẫn được giữ lại.
            </Text>
            <Pressable
              testID="retry-exercise-detail-background"
              accessibilityRole="button"
              accessibilityLabel="Thử cập nhật lại chi tiết bài tập"
              accessibilityHint="Tải lại dữ liệu mới nhất"
              onPress={() => refetch()}>
              <Text style={[styles.warningAction, { color: colors.warningText }]}>Thử lại</Text>
            </Pressable>
          </View>
        )}

        <View style={styles.hero}>
          <Text
            testID="exercise-detail-name"
            accessibilityRole="header"
            style={[styles.exerciseName, { color: colors.textPrimary }]}>
            {detail.name}
          </Text>
          <View style={styles.badges}>
            {detail.category && (
              <Text style={[styles.badge, { backgroundColor: colors.surfaceSubtle, color: colors.textPrimary }]}>
                {detail.category.name}
              </Text>
            )}
            {detail.difficulty && (
              <Text style={[styles.badge, { backgroundColor: colors.brandSoft, color: colors.primary }]}>
                {EXERCISE_DIFFICULTY_LABELS[detail.difficulty]}
              </Text>
            )}
            {detail.movementPattern && (
              <Text style={[styles.badge, { backgroundColor: colors.surfaceSubtle, color: colors.textPrimary }]}>
                Kiểu chuyển động: {detail.movementPattern}
              </Text>
            )}
            {detail.unilateral && (
              <Text style={[styles.badge, { backgroundColor: colors.surfaceSubtle, color: colors.textPrimary }]}>
                Bài tập một bên
              </Text>
            )}
          </View>
        </View>

        <View
          testID="exercise-detail-media"
          accessible
          accessibilityLabel={
            detail.mediaAvailable
              ? 'Bài tập có nội dung hướng dẫn nhưng chưa hỗ trợ phát media'
              : 'Bài tập chưa có nội dung hướng dẫn'
          }
          style={[styles.media, { backgroundColor: colors.surfaceSubtle, borderColor: colors.border }]}>
          <Text style={[styles.mediaGlyph, { color: colors.textSecondary }]}>▶</Text>
          <Text style={[styles.mediaTitle, { color: colors.textPrimary }]}>
            {detail.mediaAvailable ? 'Có nội dung hướng dẫn' : 'Chưa có nội dung hướng dẫn'}
          </Text>
          {detail.mediaAvailable && (
            <Text style={[styles.mediaBody, { color: colors.textSecondary }]}>
              Trình phát media chưa khả dụng trong phiên bản này.
            </Text>
          )}
        </View>

        {detail.description && (
          <Section title="Mô tả" colors={colors} testID="exercise-detail-description">
            <Text style={[styles.body, { color: colors.textSecondary }]}>{detail.description}</Text>
          </Section>
        )}

        {detail.instructions && (
          <Section title="Hướng dẫn thực hiện" colors={colors} testID="exercise-detail-instructions">
            <Text style={[styles.body, { color: colors.textSecondary }]}>{detail.instructions}</Text>
          </Section>
        )}

        {muscles.length > 0 && (
          <Section title="Nhóm cơ" colors={colors} testID="exercise-detail-muscles">
            <View style={styles.itemList}>
              {muscles.map((muscle) => (
                <View key={`${muscle.code}:${muscle.involvement}`} style={styles.detailRow}>
                  <Text style={[styles.itemName, { color: colors.textPrimary }]}>{muscle.name}</Text>
                  <Text style={[styles.itemMeta, { color: colors.textSecondary }]}>
                    {EXERCISE_MUSCLE_INVOLVEMENT_LABELS[muscle.involvement]}
                  </Text>
                </View>
              ))}
            </View>
          </Section>
        )}

        {equipment.length > 0 && (
          <Section title="Thiết bị" colors={colors} testID="exercise-detail-equipment">
            <View style={styles.itemList}>
              {equipment.map((item) => (
                <View key={`${item.code}:${item.requirement}`} style={styles.detailRow}>
                  <Text style={[styles.itemName, { color: colors.textPrimary }]}>{item.name}</Text>
                  <Text style={[styles.itemMeta, { color: colors.textSecondary }]}>
                    {EXERCISE_EQUIPMENT_REQUIREMENT_LABELS[item.requirement]}
                  </Text>
                </View>
              ))}
            </View>
          </Section>
        )}

        {detail.tags.length > 0 && (
          <Section title="Nhãn" colors={colors} testID="exercise-detail-tags">
            <View style={styles.badges}>
              {detail.tags.map((tag) => (
                <Text key={tag.code} style={[styles.badge, { backgroundColor: colors.surfaceSubtle, color: colors.textPrimary }]}>
                  {tag.name}
                </Text>
              ))}
            </View>
          </Section>
        )}

        {detail.guidance.length > 0 && (
          <Section title="Lưu ý kỹ thuật" colors={colors} testID="exercise-detail-guidance">
            <View style={styles.itemList}>
              {detail.guidance.map((guidance) => (
                <View key={guidance.id} style={[styles.guidanceCard, { backgroundColor: colors.surfaceSubtle }]}>
                  <Text style={[styles.guidanceType, { color: colors.primary }]}>
                    {EXERCISE_GUIDANCE_TYPE_LABELS[guidance.type]}
                  </Text>
                  <Text accessibilityRole="header" style={[styles.itemName, { color: colors.textPrimary }]}>
                    {guidance.title}
                  </Text>
                  <Text style={[styles.body, { color: colors.textSecondary }]}>{guidance.description}</Text>
                  {guidance.correction && (
                    <Text style={[styles.body, { color: colors.textSecondary }]}>
                      Cách điều chỉnh: {guidance.correction}
                    </Text>
                  )}
                </View>
              ))}
            </View>
          </Section>
        )}

        {detail.variations.length > 0 && (
          <Section title="Biến thể" colors={colors} testID="exercise-detail-variations">
            <View style={styles.itemList}>
              {detail.variations.map((variation) => (
                <View
                  key={variation.id}
                  testID={`exercise-variation-${variation.id}`}
                  style={[styles.variationCard, { borderColor: colors.border }]}>
                  <View style={styles.variationTitleRow}>
                    <Text accessibilityRole="header" style={[styles.itemName, { color: colors.textPrimary }]}>
                      {variation.name}
                    </Text>
                    {variation.defaultVariation && (
                      <Text style={[styles.badge, { backgroundColor: colors.brandSoft, color: colors.primary }]}>
                        Mặc định
                      </Text>
                    )}
                  </View>
                  {variation.difficulty && (
                    <Text style={[styles.itemMeta, { color: colors.textSecondary }]}>
                      Độ khó: {EXERCISE_DIFFICULTY_LABELS[variation.difficulty]}
                    </Text>
                  )}
                  {variation.description && (
                    <Text style={[styles.body, { color: colors.textSecondary }]}>{variation.description}</Text>
                  )}
                  {variation.instructions && (
                    <Text style={[styles.body, { color: colors.textSecondary }]}>
                      Hướng dẫn: {variation.instructions}
                    </Text>
                  )}
                </View>
              ))}
            </View>
          </Section>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    paddingHorizontal: layout.mobileScreenPadding,
    paddingTop: spacing.sm,
    paddingBottom: spacing.md,
    gap: spacing.xs,
  },
  backButton: {
    minHeight: layout.minimumTouchTarget,
    alignSelf: 'flex-start',
    justifyContent: 'center',
  },
  backText: { ...typography.label },
  screenTitle: { ...typography.h2 },
  content: {
    paddingHorizontal: layout.mobileScreenPadding,
    paddingBottom: spacing.xxl,
    gap: layout.sectionGap,
  },
  hero: { gap: spacing.md },
  exerciseName: { ...typography.h1 },
  body: { ...typography.body },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  badge: {
    ...typography.caption,
    borderRadius: radius.full,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  media: {
    minHeight: 184,
    borderWidth: 1,
    borderRadius: radius.xl,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.sm,
  },
  mediaGlyph: { ...typography.h1 },
  mediaTitle: { ...typography.h3, textAlign: 'center' },
  mediaBody: { ...typography.bodySmall, textAlign: 'center' },
  section: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.md },
  sectionTitle: { ...typography.h2 },
  itemList: { gap: spacing.md },
  detailRow: { gap: spacing.xxs },
  itemName: { ...typography.h3, flexShrink: 1 },
  itemMeta: { ...typography.bodySmall },
  guidanceCard: { borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  guidanceType: { ...typography.label },
  variationCard: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  variationTitleRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing.sm },
  warning: { borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  warningText: { ...typography.bodySmall },
  warningAction: { ...typography.label, textDecorationLine: 'underline' },
  skeleton: {
    flex: 1,
    paddingHorizontal: layout.mobileScreenPadding,
    paddingTop: spacing.xl,
    gap: spacing.lg,
  },
  skeletonHero: { height: 184, borderRadius: radius.xl },
  skeletonLine: { height: 20, borderRadius: radius.sm },
  state: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
    gap: spacing.md,
  },
  stateTitle: { ...typography.h2, textAlign: 'center' },
  stateBody: { ...typography.body, textAlign: 'center' },
  primaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.xl,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { ...typography.label },
});
