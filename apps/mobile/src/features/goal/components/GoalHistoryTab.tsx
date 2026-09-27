import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { FitnessGoalVersion, GoalTransition } from '@/types/goal';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';

interface GoalHistoryTabProps {
  goalId: string;
  isDark: boolean;
}

const PAGE_SIZE = 20;

export function GoalHistoryTab({ goalId, isDark }: GoalHistoryTabProps) {
  const [activeSubTab, setActiveSubTab] = useState<'versions' | 'transitions'>('versions');

  // Versions State
  const [versions, setVersions] = useState<FitnessGoalVersion[]>([]);
  const [versionsPage, setVersionsPage] = useState(0);
  const [versionsTotalPages, setVersionsTotalPages] = useState(1);
  const [isVersionsLoading, setIsVersionsLoading] = useState(true);
  const [isVersionsLoadingMore, setIsVersionsLoadingMore] = useState(false);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [versionsLoadMoreError, setVersionsLoadMoreError] = useState<string | null>(null);

  // Transitions State
  const [transitions, setTransitions] = useState<GoalTransition[]>([]);
  const [isTransitionsLoading, setIsTransitionsLoading] = useState(true);
  const [transitionsError, setTransitionsError] = useState<string | null>(null);

  // Stale request guards & double-click locks
  const versionsSeqRef = useRef(0);
  const transitionsSeqRef = useRef(0);
  const isVersionsLoadingMoreRef = useRef(false);

  const themeColors = getSemanticColors(isDark);

  const loadVersions = useCallback(
    async (targetPage = 0, isAppend = false) => {
      const reqId = ++versionsSeqRef.current;
      await Promise.resolve();

      if (isAppend) {
        setIsVersionsLoadingMore(true);
        setVersionsLoadMoreError(null);
      } else {
        setIsVersionsLoading(true);
        setVersionsError(null);
      }

      try {
        const res = await fitnessGoalApi.getGoalVersions(goalId, targetPage, PAGE_SIZE);
        if (reqId !== versionsSeqRef.current) return;

        if (isAppend) {
          setVersions((prev) => {
            const existingIds = new Set(prev.map((v) => v.id));
            const newItems = res.items.filter((v) => !existingIds.has(v.id));
            return [...prev, ...newItems];
          });
        } else {
          setVersions(res.items);
        }
        setVersionsPage(targetPage);
        setVersionsTotalPages(res.totalPages || 1);
      } catch (err: any) {
        if (reqId !== versionsSeqRef.current) return;
        if (isAppend) {
          setVersionsLoadMoreError(err?.message || 'Failed to load more versions.');
        } else {
          setVersionsError(err?.message || 'Failed to load goal versions.');
        }
      } finally {
        if (reqId === versionsSeqRef.current) {
          isVersionsLoadingMoreRef.current = false;
          setIsVersionsLoading(false);
          setIsVersionsLoadingMore(false);
        }
      }
    },
    [goalId]
  );

  const loadTransitions = useCallback(async () => {
    const reqId = ++transitionsSeqRef.current;
    await Promise.resolve();
    setIsTransitionsLoading(true);
    setTransitionsError(null);

    try {
      const res = await fitnessGoalApi.getGoalTransitions(goalId);
      if (reqId !== transitionsSeqRef.current) return;
      setTransitions(res);
    } catch (err: any) {
      if (reqId !== transitionsSeqRef.current) return;
      setTransitionsError(err?.message || 'Failed to load goal transitions.');
    } finally {
      if (reqId === transitionsSeqRef.current) {
        setIsTransitionsLoading(false);
      }
    }
  }, [goalId]);

  useEffect(() => {
    const vRef = versionsSeqRef;
    const tRef = transitionsSeqRef;
    const execute = async () => {
      await Promise.all([loadVersions(0, false), loadTransitions()]);
    };
    execute();
    return () => {
      vRef.current++;
      tRef.current++;
    };
  }, [loadVersions, loadTransitions]);

  const handleLoadMoreVersions = () => {
    if (isVersionsLoadingMoreRef.current || versionsPage + 1 >= versionsTotalPages) {
      return;
    }
    isVersionsLoadingMoreRef.current = true;
    loadVersions(versionsPage + 1, true);
  };

  return (
    <View style={styles.container}>
      {/* Sub-tab Switcher: Versions vs Transitions */}
      <View
        style={[
          styles.subTabRow,
          {
            backgroundColor: themeColors.surfaceSubtle,
            borderColor: themeColors.border,
          },
        ]}>
        <Pressable
          testID="subtab-versions-button"
          accessibilityRole="tab"
          accessibilityLabel="Goal Versions Tab"
          accessibilityState={{ selected: activeSubTab === 'versions' }}
          style={[
            styles.subTab,
            activeSubTab === 'versions' && [
              styles.activeSubTab,
              { backgroundColor: themeColors.surface },
            ],
          ]}
          onPress={() => setActiveSubTab('versions')}>
          <Text
            style={[
              styles.subTabText,
              {
                color:
                  activeSubTab === 'versions'
                    ? themeColors.primary
                    : themeColors.textSecondary,
                fontWeight: activeSubTab === 'versions' ? '700' : '500',
              },
            ]}>
            Goal Versions ({versions.length})
          </Text>
        </Pressable>

        <Pressable
          testID="subtab-transitions-button"
          accessibilityRole="tab"
          accessibilityLabel="Goal Transitions Tab"
          accessibilityState={{ selected: activeSubTab === 'transitions' }}
          style={[
            styles.subTab,
            activeSubTab === 'transitions' && [
              styles.activeSubTab,
              { backgroundColor: themeColors.surface },
            ],
          ]}
          onPress={() => setActiveSubTab('transitions')}>
          <Text
            style={[
              styles.subTabText,
              {
                color:
                  activeSubTab === 'transitions'
                    ? themeColors.primary
                    : themeColors.textSecondary,
                fontWeight: activeSubTab === 'transitions' ? '700' : '500',
              },
            ]}>
            Transitions ({transitions.length})
          </Text>
        </Pressable>
      </View>

      {/* Description Explaining Semantic Distinction */}
      <Text style={[styles.conceptDescription, { color: themeColors.textSecondary }]}>
        {activeSubTab === 'versions'
          ? 'Goal Versions preserve strategic milestones and adjustments within this same goal journey.'
          : 'Goal Transitions record cross-journey transitions when switching from a previous goal to a new journey.'}
      </Text>

      {/* Goal Versions List */}
      {activeSubTab === 'versions' && (
        <View style={styles.listContainer}>
          {isVersionsLoading ? (
            <View style={styles.loadingContainer}>
              <ActivityIndicator size="small" color={themeColors.primary} />
              <Text style={[styles.loadingText, { color: themeColors.textSecondary }]}>
                Loading versions...
              </Text>
            </View>
          ) : versionsError ? (
            <View
              testID="versions-error-card"
              style={[
                styles.card,
                { backgroundColor: themeColors.surface, borderColor: themeColors.border },
              ]}>
              <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                {versionsError}
              </Text>
              <Pressable
                testID="retry-versions-button"
                accessibilityRole="button"
                accessibilityLabel="Retry loading versions"
                onPress={() => loadVersions(0, false)}
                style={[styles.retryButton, { backgroundColor: themeColors.primary }]}>
                <Text style={styles.retryButtonText}>Retry</Text>
              </Pressable>
            </View>
          ) : versions.length === 0 ? (
            <View
              testID="empty-versions-card"
              style={[
                styles.emptyCard,
                { backgroundColor: themeColors.surface, borderColor: themeColors.border },
              ]}>
              <Text style={[styles.emptyText, { color: themeColors.textSecondary }]}>
                No version records found.
              </Text>
            </View>
          ) : (
            <>
              {versions.map((ver) => (
                <View
                  key={ver.id}
                  testID={`version-card-${ver.versionNumber}`}
                  style={[
                    styles.card,
                    {
                      backgroundColor: themeColors.surface,
                      borderColor: themeColors.border,
                    },
                  ]}>
                  <View style={styles.cardHeader}>
                    <Text style={[styles.versionTitle, { color: themeColors.textPrimary }]}>
                      Version {ver.versionNumber}: {ver.title || 'Untitled'}
                    </Text>
                    {ver.isCurrent && (
                      <View
                        style={[
                          styles.currentBadge,
                          { backgroundColor: themeColors.brandSoft },
                        ]}>
                        <Text style={[styles.currentBadgeText, { color: themeColors.primary }]}>
                          CURRENT
                        </Text>
                      </View>
                    )}
                  </View>

                  <View style={styles.row}>
                    <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                      Effective:
                    </Text>
                    <Text style={[styles.metaValue, { color: themeColors.textPrimary }]}>
                      {new Date(ver.effectiveFrom).toLocaleDateString()}
                      {ver.effectiveUntil
                        ? ` – ${new Date(ver.effectiveUntil).toLocaleDateString()}`
                        : ' (Present)'}
                    </Text>
                  </View>

                  {ver.changeReason && (
                    <View style={styles.row}>
                      <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                        Change Reason:
                      </Text>
                      <Text style={[styles.metaValue, { color: themeColors.textPrimary }]}>
                        {ver.changeReason}
                      </Text>
                    </View>
                  )}

                  {ver.changeSummary && (
                    <Text style={[styles.summaryText, { color: themeColors.textSecondary }]}>
                      {ver.changeSummary}
                    </Text>
                  )}

                  {ver.lockReason && (
                    <View style={styles.row}>
                      <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                        Lock Reason:
                      </Text>
                      <Text style={[styles.metaValue, { color: themeColors.textSecondary }]}>
                        {ver.lockReason}
                      </Text>
                    </View>
                  )}
                </View>
              ))}

              {versionsLoadMoreError && (
                <View style={styles.loadMoreErrorRow}>
                  <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {versionsLoadMoreError}
                  </Text>
                  <Pressable
                    testID="retry-load-more-versions-button"
                    accessibilityRole="button"
                    accessibilityLabel="Retry loading more versions"
                    style={[styles.retrySmallButton, { backgroundColor: themeColors.primary }]}
                    onPress={handleLoadMoreVersions}>
                    <Text style={styles.retryButtonText}>Retry</Text>
                  </Pressable>
                </View>
              )}

              {versionsPage + 1 < versionsTotalPages && !versionsLoadMoreError && (
                <Pressable
                  testID="load-more-versions-button"
                  accessibilityRole="button"
                  accessibilityLabel="Load more versions"
                  disabled={isVersionsLoadingMore}
                  style={[
                    styles.loadMoreButton,
                    { borderColor: themeColors.border, backgroundColor: themeColors.surface },
                  ]}
                  onPress={handleLoadMoreVersions}>
                  {isVersionsLoadingMore ? (
                    <ActivityIndicator size="small" color={themeColors.primary} />
                  ) : (
                    <Text style={[styles.loadMoreText, { color: themeColors.primary }]}>
                      Load More Versions ({versions.length} shown)
                    </Text>
                  )}
                </Pressable>
              )}
            </>
          )}
        </View>
      )}

      {/* Goal Transitions List */}
      {activeSubTab === 'transitions' && (
        <View style={styles.listContainer}>
          {isTransitionsLoading ? (
            <View style={styles.loadingContainer}>
              <ActivityIndicator size="small" color={themeColors.primary} />
              <Text style={[styles.loadingText, { color: themeColors.textSecondary }]}>
                Loading transitions...
              </Text>
            </View>
          ) : transitionsError ? (
            <View
              testID="transitions-error-card"
              style={[
                styles.card,
                { backgroundColor: themeColors.surface, borderColor: themeColors.border },
              ]}>
              <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                {transitionsError}
              </Text>
              <Pressable
                testID="retry-transitions-button"
                accessibilityRole="button"
                accessibilityLabel="Retry loading transitions"
                onPress={loadTransitions}
                style={[styles.retryButton, { backgroundColor: themeColors.primary }]}>
                <Text style={styles.retryButtonText}>Retry</Text>
              </Pressable>
            </View>
          ) : transitions.length === 0 ? (
            <View
              testID="empty-transitions-card"
              style={[
                styles.emptyCard,
                { backgroundColor: themeColors.surface, borderColor: themeColors.border },
              ]}>
              <Text style={[styles.emptyText, { color: themeColors.textSecondary }]}>
                No goal transition records found for this goal.
              </Text>
            </View>
          ) : (
            transitions.map((trans) => (
              <View
                key={trans.id}
                testID={`transition-card-${trans.id}`}
                style={[
                  styles.card,
                  {
                    backgroundColor: themeColors.surface,
                    borderColor: themeColors.border,
                  },
                ]}>
                <Text style={[styles.versionTitle, { color: themeColors.textPrimary }]}>
                  Transition: {trans.transitionReason}
                </Text>

                <View style={styles.row}>
                  <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                    Date:
                  </Text>
                  <Text style={[styles.metaValue, { color: themeColors.textPrimary }]}>
                    {new Date(trans.transitionedAt).toLocaleDateString()}
                  </Text>
                </View>

                {trans.notes && (
                  <Text style={[styles.summaryText, { color: themeColors.textSecondary }]}>
                    Notes: {trans.notes}
                  </Text>
                )}

                <View style={styles.row}>
                  <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                    Initiated by:
                  </Text>
                  <Text style={[styles.metaValue, { color: themeColors.textPrimary }]}>
                    {trans.initiatedBy}
                  </Text>
                </View>
              </View>
            ))
          )}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.md,
  },
  loadingContainer: {
    padding: spacing.xl,
    alignItems: 'center',
    gap: spacing.sm,
  },
  loadingText: {
    ...typography.bodySmall,
  },
  errorText: {
    ...typography.bodySmall,
    textAlign: 'center',
    marginBottom: spacing.xs,
  },
  retryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    justifyContent: 'center',
    alignItems: 'center',
    alignSelf: 'center',
    paddingHorizontal: spacing.lg,
  },
  retrySmallButton: {
    minHeight: 36,
    borderRadius: radius.sm,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
  },
  retryButtonText: {
    ...typography.label,
    color: '#FFFFFF',
  },
  loadMoreErrorRow: {
    padding: spacing.sm,
    alignItems: 'center',
    gap: spacing.xs,
  },
  subTabRow: {
    flexDirection: 'row',
    borderRadius: radius.md,
    borderWidth: 1,
    padding: 3,
  },
  subTab: {
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: radius.sm,
  },
  activeSubTab: {
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 1,
  },
  subTabText: {
    ...typography.label,
  },
  conceptDescription: {
    ...typography.caption,
    fontStyle: 'italic',
    paddingHorizontal: spacing.xs,
  },
  listContainer: {
    gap: spacing.sm,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.md,
    borderWidth: 1,
    gap: spacing.xs,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  versionTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  currentBadge: {
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
    borderRadius: radius.sm,
  },
  currentBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  row: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  metaLabel: {
    ...typography.caption,
    fontWeight: '600',
  },
  metaValue: {
    ...typography.caption,
  },
  summaryText: {
    ...typography.caption,
    marginTop: spacing.xxs,
  },
  emptyCard: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    alignItems: 'center',
  },
  emptyText: {
    ...typography.bodySmall,
  },
  loadMoreButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    marginTop: spacing.xs,
  },
  loadMoreText: {
    ...typography.label,
    fontWeight: '600',
  },
});
