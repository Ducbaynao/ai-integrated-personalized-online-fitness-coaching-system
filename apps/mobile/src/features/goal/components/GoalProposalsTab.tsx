import React, { useCallback, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useFocusEffect, useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { GoalProposal, ProposalStatus } from '@/types/goal';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';

interface GoalProposalsTabProps {
  goalId?: string;
  isDark: boolean;
  onSelectProposal?: (proposalId: string) => void;
}

type FilterOption = 'ALL' | 'PENDING' | 'ACCEPTED' | 'REJECTED';

const PAGE_SIZE = 20;

export function GoalProposalsTab({ goalId, isDark, onSelectProposal }: GoalProposalsTabProps) {
  const router = useRouter();
  const themeColors = getSemanticColors(isDark);

  const [activeFilter, setActiveFilter] = useState<FilterOption>('ALL');
  const [proposals, setProposals] = useState<GoalProposal[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // Stale request guards & double-click locks
  const requestSeqRef = useRef(0);
  const isLoadingMoreRef = useRef(false);

  const loadProposals = useCallback(
    async (
      targetPage = 0,
      isAppend = false,
      filterToUse: FilterOption = activeFilter
    ) => {
      const reqId = ++requestSeqRef.current;
      await Promise.resolve();

      if (isAppend) {
        setIsLoadingMore(true);
        setLoadMoreError(null);
      } else {
        setIsLoading(true);
        setError(null);
        setLoadMoreError(null);
      }

      try {
        const statusParam = filterToUse === 'ALL' ? undefined : (filterToUse as ProposalStatus);
        const res = await fitnessGoalApi.getMyGoalProposals({
          status: statusParam,
          fitnessGoalId: goalId,
          page: targetPage,
          size: PAGE_SIZE,
        });

        // Stale guard: verify request is still the latest and filter hasn't changed
        if (reqId !== requestSeqRef.current) return;

        if (isAppend) {
          setProposals((prev) => {
            const existingIds = new Set(prev.map((p) => p.id));
            const newItems = res.items.filter((p) => !existingIds.has(p.id));
            return [...prev, ...newItems];
          });
        } else {
          setProposals(res.items);
        }
        setPage(targetPage);
        setTotalPages(res.totalPages || 1);
      } catch (err: any) {
        if (reqId !== requestSeqRef.current) return;
        if (isAppend) {
          setLoadMoreError(err?.message || 'Failed to load more proposals.');
        } else {
          setError(err?.message || 'Failed to load goal proposals.');
        }
      } finally {
        if (reqId === requestSeqRef.current) {
          isLoadingMoreRef.current = false;
          setIsLoading(false);
          setIsLoadingMore(false);
        }
      }
    },
    [goalId, activeFilter]
  );

  // Invalidate in-flight and reset on filter or goalId change
  const handleFilterChange = (newFilter: FilterOption) => {
    if (newFilter === activeFilter) return;
    requestSeqRef.current++;
    setActiveFilter(newFilter);
    setPage(0);
    setTotalPages(1);
    setProposals([]);
    setError(null);
    setLoadMoreError(null);
  };

  useFocusEffect(
    useCallback(() => {
      const seqRef = requestSeqRef;
      const execute = async () => {
        await loadProposals(0, false, activeFilter);
      };
      execute();
      return () => {
        seqRef.current++;
      };
    }, [loadProposals, activeFilter])
  );

  const handleLoadMore = () => {
    if (isLoadingMoreRef.current || page + 1 >= totalPages) {
      return;
    }
    isLoadingMoreRef.current = true;
    loadProposals(page + 1, true, activeFilter);
  };

  const handleProposalPress = (proposalId: string) => {
    if (onSelectProposal) {
      onSelectProposal(proposalId);
    } else {
      router.push(`/goal-proposals/${proposalId}` as any);
    }
  };

  const getProposalStatusColor = (status: ProposalStatus) => {
    switch (status) {
      case 'PENDING':
        return {
          bg: themeColors.warningSurface,
          text: themeColors.warningText,
          border: themeColors.warningText,
        };
      case 'ACCEPTED':
        return {
          bg: themeColors.successSurface,
          text: themeColors.successText,
          border: themeColors.successText,
        };
      case 'REJECTED':
      case 'CANCELLED':
      case 'EXPIRED':
        return {
          bg: themeColors.surfaceSubtle,
          text: themeColors.textSecondary,
          border: themeColors.border,
        };
    }
  };

  return (
    <View style={styles.container}>
      {/* Filter Tabs */}
      <View
        style={[
          styles.filterRow,
          {
            backgroundColor: themeColors.surfaceSubtle,
            borderColor: themeColors.border,
          },
        ]}>
        {(['ALL', 'PENDING', 'ACCEPTED', 'REJECTED'] as FilterOption[]).map((tab) => (
          <Pressable
            key={tab}
            testID={`proposals-filter-${tab.toLowerCase()}`}
            accessibilityRole="tab"
            accessibilityLabel={`Filter by ${tab} proposals`}
            accessibilityState={{ selected: activeFilter === tab }}
            style={[
              styles.filterTab,
              activeFilter === tab && [
                styles.activeFilterTab,
                { backgroundColor: themeColors.surface },
              ],
            ]}
            onPress={() => handleFilterChange(tab)}>
            <Text
              style={[
                styles.filterTabText,
                {
                  color:
                    activeFilter === tab
                      ? themeColors.primary
                      : themeColors.textSecondary,
                  fontWeight: activeFilter === tab ? '700' : '500',
                },
              ]}>
              {tab === 'ALL' ? 'All' : tab.charAt(0) + tab.slice(1).toLowerCase()}
            </Text>
          </Pressable>
        ))}
      </View>

      {/* Content */}
      {isLoading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="small" color={themeColors.primary} />
          <Text style={[styles.loadingText, { color: themeColors.textSecondary }]}>
            Loading proposals...
          </Text>
        </View>
      ) : error ? (
        <View
          testID="proposals-error-card"
          style={[
            styles.card,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.errorText, { color: themeColors.dangerText }]}>{error}</Text>
          <Pressable
            testID="retry-proposals-button"
            accessibilityRole="button"
            accessibilityLabel="Retry loading proposals"
            onPress={() => loadProposals(0, false, activeFilter)}
            style={[styles.retryButton, { backgroundColor: themeColors.primary }]}>
            <Text style={styles.retryButtonText}>Retry</Text>
          </Pressable>
        </View>
      ) : proposals.length === 0 ? (
        <View
          testID="empty-proposals-card"
          style={[
            styles.emptyCard,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.emptyTitle, { color: themeColors.textPrimary }]}>
            No Proposals Found
          </Text>
          <Text style={[styles.emptySubtitle, { color: themeColors.textSecondary }]}>
            {activeFilter === 'PENDING'
              ? 'You have no pending goal proposals waiting for review.'
              : 'There are no proposals matching this filter.'}
          </Text>
        </View>
      ) : (
        <View style={styles.listContainer}>
          {proposals.map((proposal) => {
            const statusStyle = getProposalStatusColor(proposal.status);
            return (
              <Pressable
                key={proposal.id}
                testID={`proposal-item-${proposal.id}`}
                accessibilityRole="button"
                accessibilityLabel={`View proposal details: ${proposal.reason}. Status: ${proposal.status}`}
                onPress={() => handleProposalPress(proposal.id)}
                style={({ pressed }) => [
                  styles.card,
                  {
                    backgroundColor: pressed ? themeColors.surfaceSubtle : themeColors.surface,
                    borderColor:
                      proposal.status === 'PENDING' ? themeColors.warningText : themeColors.border,
                  },
                ]}>
                <View style={styles.cardHeader}>
                  <View style={styles.sourceTag}>
                    <Text style={[styles.sourceText, { color: themeColors.textSecondary }]}>
                      From: {proposal.source}
                    </Text>
                  </View>
                  <View
                    style={[
                      styles.statusBadge,
                      {
                        backgroundColor: statusStyle.bg,
                        borderColor: statusStyle.border,
                      },
                    ]}>
                    <Text style={[styles.statusBadgeText, { color: statusStyle.text }]}>
                      {proposal.status}
                    </Text>
                  </View>
                </View>

                {proposal.proposedTitle && (
                  <Text style={[styles.proposalTitle, { color: themeColors.textPrimary }]}>
                    Proposed Title: {proposal.proposedTitle}
                  </Text>
                )}

                <Text
                  numberOfLines={2}
                  style={[styles.reasonText, { color: themeColors.textPrimary }]}>
                  {`"${proposal.reason}"`}
                </Text>

                <View style={styles.metaRow}>
                  <Text style={[styles.metaText, { color: themeColors.textSecondary }]}>
                    Proposed {new Date(proposal.createdAt).toLocaleDateString()}
                  </Text>
                  <Text style={[styles.arrowText, { color: themeColors.primary }]}>
                    Review →
                  </Text>
                </View>
              </Pressable>
            );
          })}

          {loadMoreError && (
            <View style={styles.loadMoreErrorRow}>
              <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                {loadMoreError}
              </Text>
              <Pressable
                testID="retry-load-more-proposals-button"
                accessibilityRole="button"
                accessibilityLabel="Retry loading more proposals"
                style={[styles.retrySmallButton, { backgroundColor: themeColors.primary }]}
                onPress={handleLoadMore}>
                <Text style={styles.retryButtonText}>Retry</Text>
              </Pressable>
            </View>
          )}

          {page + 1 < totalPages && !loadMoreError && (
            <Pressable
              testID="load-more-proposals-button"
              accessibilityRole="button"
              accessibilityLabel="Load more proposals"
              disabled={isLoadingMore}
              style={[
                styles.loadMoreButton,
                { borderColor: themeColors.border, backgroundColor: themeColors.surface },
              ]}
              onPress={handleLoadMore}>
              {isLoadingMore ? (
                <ActivityIndicator size="small" color={themeColors.primary} />
              ) : (
                <Text style={[styles.loadMoreText, { color: themeColors.primary }]}>
                  Load More Proposals ({proposals.length} shown)
                </Text>
              )}
            </Pressable>
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
  filterRow: {
    flexDirection: 'row',
    borderRadius: radius.md,
    borderWidth: 1,
    padding: 3,
  },
  filterTab: {
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: radius.sm,
  },
  activeFilterTab: {
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 1,
  },
  filterTabText: {
    ...typography.label,
  },
  loadingContainer: {
    padding: spacing.xl,
    alignItems: 'center',
    gap: spacing.sm,
  },
  loadingText: {
    ...typography.bodySmall,
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
  sourceTag: {
    paddingVertical: 2,
  },
  sourceText: {
    ...typography.caption,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  statusBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.sm,
    borderWidth: 1,
  },
  statusBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  proposalTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  reasonText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: spacing.xxs,
  },
  metaText: {
    ...typography.caption,
  },
  arrowText: {
    ...typography.label,
  },
  emptyCard: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    alignItems: 'center',
    gap: spacing.xs,
  },
  emptyTitle: {
    ...typography.h3,
  },
  emptySubtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
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
