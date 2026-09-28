import React, { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Href, useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { useExerciseCatalog, useExerciseFilterMetadata } from './exerciseQueries';
import { ExerciseListItem } from './components/ExerciseListItem';
import { SearchAndFilterBar } from './components/SearchAndFilterBar';
import { ExerciseFilterModal } from './components/ExerciseFilterModal';
import { ExerciseCatalogFilters, ExerciseFilterMetadata } from '@/types/exercise';
import {
  EXERCISE_DIFFICULTY_LABELS,
  getExerciseCatalogErrorCopy,
  isExerciseAccessError,
} from './exerciseMessages';

type ArrayFilterKey = Exclude<keyof ExerciseCatalogFilters, 'query'>;

const FILTER_LABELS: Record<ArrayFilterKey, string> = {
  categoryCodes: 'Danh mục',
  muscleGroupCodes: 'Nhóm cơ',
  equipmentCodes: 'Thiết bị',
  tagCodes: 'Nhãn',
  difficulties: 'Độ khó',
  movementPatterns: 'Kiểu chuyển động',
};

function optionName(metadata: ExerciseFilterMetadata | undefined, key: ArrayFilterKey, code: string) {
  if (key === 'difficulties') {
    return EXERCISE_DIFFICULTY_LABELS[code as keyof typeof EXERCISE_DIFFICULTY_LABELS] ?? code;
  }
  if (key === 'movementPatterns') return code;
  const metadataKey = {
    categoryCodes: 'categories',
    muscleGroupCodes: 'muscleGroups',
    equipmentCodes: 'equipment',
    tagCodes: 'tags',
  }[key] as 'categories' | 'muscleGroups' | 'equipment' | 'tags';
  return metadata?.[metadataKey].find((option) => option.code === code)?.name ?? code;
}

function hasFilters(filters: ExerciseCatalogFilters): boolean {
  return Object.values(filters).some((values) => Array.isArray(values) && values.length > 0);
}

function CatalogSkeleton({ colors }: { colors: ReturnType<typeof getSemanticColors> }) {
  return (
    <View testID="exercise-catalog-loading" accessibilityRole="progressbar" accessibilityLabel="Đang tải thư viện bài tập" style={styles.skeletonList}>
      {[0, 1, 2].map((item) => (
        <View key={item} style={[styles.skeletonCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <View style={[styles.skeletonMedia, { backgroundColor: colors.surfaceSubtle }]} />
          <View style={styles.skeletonContent}>
            <View style={[styles.skeletonLineWide, { backgroundColor: colors.surfaceSubtle }]} />
            <View style={[styles.skeletonLine, { backgroundColor: colors.surfaceSubtle }]} />
            <View style={[styles.skeletonLine, { backgroundColor: colors.surfaceSubtle }]} />
          </View>
        </View>
      ))}
    </View>
  );
}

export function ExerciseCatalogScreen() {
  const router = useRouter();
  const isDark = useColorScheme() === 'dark';
  const colors = getSemanticColors(isDark);
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<ExerciseCatalogFilters>({});
  const [filterModalVisible, setFilterModalVisible] = useState(false);
  const debouncedSearch = useDebouncedValue(search, 350);
  const queryFilters = useMemo(
    () => ({ ...filters, ...(debouncedSearch.trim() ? { query: debouncedSearch } : {}) }),
    [debouncedSearch, filters]
  );
  const catalog = useExerciseCatalog(queryFilters);
  const metadata = useExerciseFilterMetadata();
  const exercises = useMemo(() => catalog.data?.pages.flatMap((page) => page.items) ?? [], [catalog.data]);

  const chips = useMemo(
    () =>
      (Object.entries(filters) as [ArrayFilterKey, readonly string[]][]).flatMap(([key, values]) =>
        (values ?? []).map((value) => ({
          key: `${key}:${value}`,
          label: `${FILTER_LABELS[key]}: ${optionName(metadata.data, key, value)}`,
        }))
      ),
    [filters, metadata.data]
  );

  const removeChip = (chipKey: string) => {
    const separator = chipKey.indexOf(':');
    const key = chipKey.slice(0, separator) as ArrayFilterKey;
    const value = chipKey.slice(separator + 1);
    setFilters((current) => ({
      ...current,
      [key]: ((current[key] ?? []) as readonly string[]).filter((item) => item !== value),
    }));
  };

  const clearSearchAndFilters = () => {
    setSearch('');
    setFilters({});
  };

  const loadMore = () => {
    if (catalog.hasNextPage && !catalog.isFetchingNextPage) {
      catalog.fetchNextPage().catch(() => {});
    }
  };

  const isNoResult = Boolean(debouncedSearch.trim()) || hasFilters(filters);
  const errorCopy = getExerciseCatalogErrorCopy(catalog.error);
  const accessError = isExerciseAccessError(catalog.error);

  const header = (
    <View testID="exercise-catalog-header" style={styles.headerContent}>
      <View style={styles.titleRow}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Quay lại"
          onPress={() => router.back()}
          style={styles.backButton}>
          <Text style={[styles.backText, { color: colors.primary }]}>‹ Quay lại</Text>
        </Pressable>
        <Text accessibilityRole="header" style={[styles.title, { color: colors.textPrimary }]}>Thư viện bài tập</Text>
        <Text style={[styles.subtitle, { color: colors.textSecondary }]}>Tìm bài tập phù hợp theo nhóm cơ, thiết bị và độ khó.</Text>
      </View>
      <SearchAndFilterBar
        value={search}
        onChangeText={setSearch}
        onOpenFilters={() => setFilterModalVisible(true)}
        filtersExpanded={filterModalVisible}
        chips={chips}
        onRemoveChip={removeChip}
        colors={colors}
      />
      {catalog.isRefetchError && exercises.length > 0 && !catalog.isFetchNextPageError && !accessError && (
        <View testID="exercise-background-error" accessibilityRole="alert" style={[styles.warning, { backgroundColor: colors.warningSurface }]}>
          <Text style={[styles.warningText, { color: colors.warningText }]}>Không thể cập nhật dữ liệu mới. Danh sách gần nhất vẫn được giữ lại.</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="Thử cập nhật lại danh sách" accessibilityHint="Tải lại dữ liệu mới nhất" onPress={() => catalog.refetch()}>
            <Text style={[styles.warningAction, { color: colors.warningText }]}>Thử lại</Text>
          </Pressable>
        </View>
      )}
    </View>
  );

  if (catalog.isPending) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
        <View testID="exercise-catalog-standalone-header" style={styles.standaloneHeader}>{header}</View>
        <CatalogSkeleton colors={colors} />
        {filterModalVisible && <ExerciseFilterModal
          visible={filterModalVisible}
          filters={filters}
          metadata={metadata.data}
          isLoading={metadata.isPending}
          isError={metadata.isError}
          onRetryMetadata={() => metadata.refetch()}
          onApply={(next) => { setFilters(next); setFilterModalVisible(false); }}
          onClose={() => setFilterModalVisible(false)}
          isDark={isDark}
        />}
      </SafeAreaView>
    );
  }

  if (catalog.isError && (exercises.length === 0 || accessError)) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
        <View testID="exercise-catalog-standalone-header" style={styles.standaloneHeader}>{header}</View>
        <View testID="exercise-catalog-error" accessibilityRole="alert" style={[styles.centerState, styles.standaloneCenterState]}>
          <Text style={[styles.stateTitle, { color: colors.textPrimary }]}>{errorCopy.title}</Text>
          <Text style={[styles.stateBody, { color: colors.textSecondary }]}>{errorCopy.message}</Text>
          {!accessError && (
            <Pressable
              testID="retry-exercise-catalog"
              accessibilityRole="button"
              accessibilityLabel="Thử tải lại thư viện bài tập"
              accessibilityHint="Gửi lại yêu cầu tải danh sách bài tập"
              onPress={() => catalog.refetch()}
              style={[styles.primaryButton, { backgroundColor: colors.primary }]}>
              <Text style={[styles.buttonText, { color: colors.textOnPrimary }]}>Thử lại</Text>
            </Pressable>
          )}
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
      <FlatList
        testID="exercise-catalog-list"
        data={exercises}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <ExerciseListItem
            exercise={item}
            isDark={isDark}
            onPress={() =>
              router.push(
                {
                  pathname: '/(app)/exercises/[exerciseId]',
                  params: { exerciseId: item.id },
                } as unknown as Href
              )
            }
          />
        )}
        ItemSeparatorComponent={() => <View style={styles.separator} />}
        ListHeaderComponent={header}
        contentContainerStyle={styles.listContent}
        refreshControl={<RefreshControl refreshing={catalog.isRefetching && !catalog.isFetchingNextPage} onRefresh={() => catalog.refetch()} tintColor={colors.primary} />}
        onEndReached={loadMore}
        onEndReachedThreshold={0.35}
        ListEmptyComponent={
          <View testID={isNoResult ? 'exercise-no-results' : 'exercise-empty-catalog'} style={styles.centerState}>
            <Text style={[styles.stateTitle, { color: colors.textPrimary }]}>{isNoResult ? 'Không tìm thấy bài tập phù hợp' : 'Thư viện chưa có bài tập'}</Text>
            <Text style={[styles.stateBody, { color: colors.textSecondary }]}>{isNoResult ? 'Thử thay đổi từ khóa hoặc xóa bớt bộ lọc.' : 'Nội dung bài tập sẽ xuất hiện tại đây khi được cập nhật.'}</Text>
            {isNoResult && (
              <Pressable testID="clear-search-and-filters" accessibilityRole="button" accessibilityLabel="Xóa tìm kiếm và toàn bộ bộ lọc" onPress={clearSearchAndFilters} style={[styles.secondaryButton, { borderColor: colors.primary }]}>
                <Text style={[styles.buttonText, { color: colors.primary }]}>Xóa tìm kiếm và bộ lọc</Text>
              </Pressable>
            )}
          </View>
        }
        ListFooterComponent={
          <View style={styles.footer}>
            {catalog.isFetchingNextPage && <ActivityIndicator testID="exercise-load-more-indicator" accessibilityRole="progressbar" accessibilityLabel="Đang tải thêm bài tập" color={colors.primary} />}
            {catalog.isFetchNextPageError && (
              <View testID="exercise-load-more-error" accessibilityRole="alert" style={styles.loadMoreError}>
                <Text style={[styles.stateBody, { color: colors.dangerText }]}>Không thể tải thêm bài tập.</Text>
                <Pressable testID="retry-load-more" accessibilityRole="button" accessibilityLabel="Thử tải thêm bài tập" accessibilityHint="Gửi lại yêu cầu tải trang bài tập tiếp theo" onPress={loadMore} style={[styles.secondaryButton, { borderColor: colors.primary }]}>
                  <Text style={[styles.buttonText, { color: colors.primary }]}>Thử lại</Text>
                </Pressable>
              </View>
            )}
          </View>
        }
      />
      {filterModalVisible && <ExerciseFilterModal
        visible={filterModalVisible}
        filters={filters}
        metadata={metadata.data}
        isLoading={metadata.isPending}
        isError={metadata.isError}
        onRetryMetadata={() => metadata.refetch()}
        onApply={(next) => { setFilters(next); setFilterModalVisible(false); }}
        onClose={() => setFilterModalVisible(false)}
        isDark={isDark}
      />}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  listContent: { paddingHorizontal: layout.mobileScreenPadding, paddingBottom: spacing.xxl },
  headerContent: { paddingTop: spacing.md, paddingBottom: spacing.lg, gap: spacing.lg },
  standaloneHeader: { paddingHorizontal: layout.mobileScreenPadding },
  titleRow: { gap: spacing.xs },
  backButton: { minHeight: layout.minimumTouchTarget, alignSelf: 'flex-start', justifyContent: 'center' },
  backText: { ...typography.label },
  title: { ...typography.h1 },
  subtitle: { ...typography.bodySmall },
  separator: { height: spacing.md },
  warning: { borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  warningText: { ...typography.bodySmall },
  warningAction: { ...typography.label, textDecorationLine: 'underline' },
  centerState: { alignItems: 'center', paddingVertical: spacing.xxl, gap: spacing.md },
  standaloneCenterState: { paddingHorizontal: spacing.xl },
  stateTitle: { ...typography.h3, textAlign: 'center' },
  stateBody: { ...typography.bodySmall, textAlign: 'center' },
  primaryButton: { minHeight: layout.minimumTouchTarget, borderRadius: radius.md, paddingHorizontal: spacing.xl, alignItems: 'center', justifyContent: 'center' },
  secondaryButton: { minHeight: layout.minimumTouchTarget, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.lg, alignItems: 'center', justifyContent: 'center' },
  buttonText: { ...typography.label },
  footer: { minHeight: spacing.xxl * 2, alignItems: 'center', justifyContent: 'center' },
  loadMoreError: { alignItems: 'center', gap: spacing.sm },
  skeletonList: { paddingHorizontal: layout.mobileScreenPadding, gap: spacing.md },
  skeletonCard: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, flexDirection: 'row', gap: spacing.md },
  skeletonMedia: { width: 88, height: 88, borderRadius: radius.md },
  skeletonContent: { flex: 1, gap: spacing.sm, justifyContent: 'center' },
  skeletonLineWide: { height: 20, width: '85%', borderRadius: radius.sm },
  skeletonLine: { height: 14, width: '65%', borderRadius: radius.sm },
});
