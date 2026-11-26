import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import {
  daysInMonth,
  formatDateOnly,
  formatVietnameseDateDisplay,
  getCalendarGridDays,
  getTodayDateOnly,
  isDateOnlyWithinBounds,
  isMondayDateOnly,
  parseDateOnly,
} from '@/features/shared/dateInputUtils';

export interface DatePickerModalProps {
  visible: boolean;
  title: string;
  value: string;
  initialBrowseDate?: string;
  minDate?: string;
  maxDate?: string;
  yearRange?: { min: number; max: number };
  allowClear?: boolean;
  mondayOnly?: boolean;
  sessionKey?: string | number;
  onConfirm: (nextDate: string) => void;
  onClear?: () => void;
  onCancel: () => void;
}

type PickerViewMode = 'days' | 'months' | 'years';

const WEEKDAY_HEADERS = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];

function clampYearMonth(
  year: number,
  month: number,
  minYear: number,
  maxYear: number
): { year: number; month: number } {
  const clampedYear = Math.max(minYear, Math.min(maxYear, year));
  const clampedMonth = Math.max(1, Math.min(12, month));
  return { year: clampedYear, month: clampedMonth };
}

export function DatePickerModal({
  visible,
  title,
  value,
  initialBrowseDate,
  minDate,
  maxDate,
  yearRange,
  allowClear = false,
  mondayOnly = false,
  sessionKey = 0,
  onConfirm,
  onClear,
  onCancel,
}: DatePickerModalProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const { width: rawViewportWidth } = useWindowDimensions();
  const viewportWidth = rawViewportWidth > 0 ? rawViewportWidth : 360;

  const todayParts = useMemo(() => parseDateOnly(getTodayDateOnly())!, []);
  const yearRangeMin = yearRange?.min;
  const yearRangeMax = yearRange?.max;

  const [extraEarlierYears, setExtraEarlierYears] = useState(0);

  const maxYear = useMemo(() => {
    const parsedMax = parseDateOnly(maxDate);
    if (yearRangeMax !== undefined) {
      return parsedMax ? Math.min(yearRangeMax, parsedMax.year) : yearRangeMax;
    }
    if (parsedMax) return parsedMax.year;
    return Math.max(todayParts.year + 10, 2035);
  }, [maxDate, todayParts.year, yearRangeMax]);

  const computeBrowseFloorYear = (seedYear: number, extraYears: number): number => {
    const parsedMin = parseDateOnly(minDate);
    if (parsedMin) {
      return yearRangeMin !== undefined ? Math.max(yearRangeMin, parsedMin.year) : parsedMin.year;
    }
    const hintMin = yearRangeMin !== undefined ? Math.min(yearRangeMin, seedYear) : Math.min(1900, seedYear - 10);
    return Math.max(1, hintMin - extraYears);
  };

  const resolveInitialBrowse = (extraYears: number) => {
    const parsedValue = parseDateOnly(value);
    const parsedInitial = parseDateOnly(initialBrowseDate);
    const parsedMax = parseDateOnly(maxDate);
    const parsedMin = parseDateOnly(minDate);
    const seed = parsedValue ?? parsedInitial ?? (parsedMax && parsedMax.year < todayParts.year ? parsedMax : todayParts) ?? parsedMin!;
    const floor = computeBrowseFloorYear(seed.year, extraYears);
    return clampYearMonth(seed.year, seed.month, floor, maxYear);
  };

  const [viewMode, setViewMode] = useState<PickerViewMode>('days');
  const [browseYear, setBrowseYear] = useState<number>(() => resolveInitialBrowse(0).year);
  const [browseMonth, setBrowseMonth] = useState<number>(() => resolveInitialBrowse(0).month);
  const [draftDate, setDraftDate] = useState<string | null>(() =>
    parseDateOnly(value) ? value.trim() : null
  );
  const [invalidDayNotice, setInvalidDayNotice] = useState<string | null>(null);
  const [modalOpId, setModalOpId] = useState<number>(visible ? 1 : 0);
  const [prevVisible, setPrevVisible] = useState(visible);
  const [prevSessionKey, setPrevSessionKey] = useState(sessionKey);

  if (prevVisible !== visible || prevSessionKey !== sessionKey) {
    setPrevVisible(visible);
    setPrevSessionKey(sessionKey);
    if (visible) {
      const nextBrowse = resolveInitialBrowse(0);
      setModalOpId((id) => id + 1);
      setExtraEarlierYears(0);
      setViewMode('days');
      setBrowseYear(nextBrowse.year);
      setBrowseMonth(nextBrowse.month);
      setDraftDate(parseDateOnly(value) ? value.trim() : null);
      setInvalidDayNotice(null);
    }
  }

  const isMountedRef = useRef(true);
  const activeModalOpRef = useRef<{ opId: number; sessionKey: string | number; open: boolean } | null>(
    visible ? { opId: modalOpId, sessionKey, open: true } : null
  );

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      activeModalOpRef.current = null;
    };
  }, []);

  useEffect(() => {
    activeModalOpRef.current = visible
      ? { opId: modalOpId, sessionKey, open: true }
      : null;
  }, [modalOpId, sessionKey, visible]);

  const minYear = useMemo(() => {
    const parsedMin = parseDateOnly(minDate);
    if (parsedMin) {
      return yearRangeMin !== undefined ? Math.max(yearRangeMin, parsedMin.year) : parsedMin.year;
    }
    const parsedValue = parseDateOnly(value);
    const parsedDraft = parseDateOnly(draftDate);
    const baseMin = yearRangeMin !== undefined ? yearRangeMin : 1900;
    const earliestSeed = Math.min(
      baseMin,
      browseYear,
      parsedValue ? parsedValue.year - (yearRangeMin !== undefined ? 0 : 10) : baseMin,
      parsedDraft ? parsedDraft.year - (yearRangeMin !== undefined ? 0 : 10) : baseMin
    );
    return Math.max(1, earliestSeed - extraEarlierYears);
  }, [browseYear, draftDate, extraEarlierYears, minDate, value, yearRangeMin]);

  const hasUnboundedEarlierYears = !minDate && minYear > 1;

  const years = useMemo(() => {
    const list: number[] = [];
    for (let y = maxYear; y >= minYear; y -= 1) {
      list.push(y);
    }
    return list;
  }, [maxYear, minYear]);

  const calendarCells = useMemo(
    () => getCalendarGridDays(browseYear, browseMonth),
    [browseYear, browseMonth]
  );

  const weekRows = useMemo(() => {
    const rows: (number | null)[][] = [];
    for (let i = 0; i < calendarCells.length; i += 7) {
      rows.push(calendarCells.slice(i, i + 7));
    }
    return rows;
  }, [calendarCells]);

  const isDaySelectable = (iso: string): boolean => {
    if (!isDateOnlyWithinBounds(iso, minDate, maxDate)) return false;
    if (mondayOnly && !isMondayDateOnly(iso)) return false;
    return true;
  };

  const handleShiftMonth = (delta: number) => {
    let nextMonth = browseMonth + delta;
    let nextYear = browseYear;
    if (nextMonth < 1) {
      nextMonth = 12;
      nextYear -= 1;
    } else if (nextMonth > 12) {
      nextMonth = 1;
      nextYear += 1;
    }
    if (nextYear > maxYear) return;
    if (nextYear < minYear && (!hasUnboundedEarlierYears || nextYear < 1)) return;
    setBrowseYear(nextYear);
    setBrowseMonth(nextMonth);
  };

  const handleSelectDay = (day: number) => {
    const iso = formatDateOnly({ year: browseYear, month: browseMonth, day });
    if (!isDaySelectable(iso)) return;
    setDraftDate(iso);
    setInvalidDayNotice(null);
  };

  const applyBrowseYearMonthChange = (nextYear: number, nextMonth: number) => {
    setBrowseYear(nextYear);
    setBrowseMonth(nextMonth);
    setViewMode('days');

    if (!draftDate) {
      setInvalidDayNotice(null);
      return;
    }

    const currentParts = parseDateOnly(draftDate);
    if (!currentParts) {
      setDraftDate(null);
      setInvalidDayNotice(null);
      return;
    }

    const maxDay = daysInMonth(nextYear, nextMonth);
    if (currentParts.day > maxDay) {
      setDraftDate(null);
      setInvalidDayNotice(
        `Ngày ${currentParts.day} không tồn tại trong Tháng ${nextMonth}/${nextYear}. Vui lòng chọn ngày khác hoặc bấm Hủy để giữ nguyên.`
      );
      return;
    }

    const candidate = formatDateOnly({ year: nextYear, month: nextMonth, day: currentParts.day });
    if (isDaySelectable(candidate)) {
      setDraftDate(candidate);
      setInvalidDayNotice(null);
      return;
    }

    setDraftDate(null);
    setInvalidDayNotice(
      `Ngày ${formatVietnameseDateDisplay(candidate)} không hợp lệ với giới hạn hiện tại. Vui lòng chọn ngày khác hoặc bấm Hủy để giữ nguyên.`
    );
  };

  const handleSelectMonth = (month: number) => {
    applyBrowseYearMonthChange(browseYear, month);
  };

  const handleSelectYear = (year: number) => {
    applyBrowseYearMonthChange(year, browseMonth);
  };

  const canConfirm = Boolean(draftDate && isDaySelectable(draftDate));

  const isModalOpActive = (opId: number): boolean => {
    const active = activeModalOpRef.current;
    return Boolean(
      isMountedRef.current &&
        visible &&
        active &&
        active.open &&
        active.opId === opId &&
        active.sessionKey === sessionKey
    );
  };

  const handleConfirmForOp = (opId: number) => {
    if (!canConfirm || !draftDate || !isModalOpActive(opId)) return;
    activeModalOpRef.current = null;
    onConfirm(draftDate);
  };

  const handleClearForOp = (opId: number) => {
    if (!allowClear || !onClear || !isModalOpActive(opId)) return;
    activeModalOpRef.current = null;
    setDraftDate(null);
    setInvalidDayNotice(null);
    onClear();
  };

  const handleCancelForOp = (opId: number) => {
    if (!isModalOpActive(opId)) return;
    activeModalOpRef.current = null;
    onCancel();
  };

  const isCompactViewport = viewportWidth < 420;
  const overlayPaddingH = isCompactViewport ? spacing.xs : spacing.lg;
  const dialogPaddingH = isCompactViewport ? spacing.sm : spacing.lg;
  const dialogBorderWidth = 1;
  const dialogWidth = Math.min(420, Math.max(280, viewportWidth - overlayPaddingH * 2));
  const gridAvailableWidth = Math.max(240, dialogWidth - dialogBorderWidth * 2 - dialogPaddingH * 2);
  const cellGap = isCompactViewport ? 2 : spacing.xs;
  const dayCellSize = Math.max(
    layout.minimumTouchTarget,
    Math.floor((gridAvailableWidth - cellGap * 6) / 7)
  );
  const optionGap = isCompactViewport ? spacing.xs : spacing.sm;
  const monthCellWidth = Math.max(
    layout.minimumTouchTarget,
    Math.floor((gridAvailableWidth - optionGap * 2) / 3)
  );
  const yearCellWidth = Math.max(
    layout.minimumTouchTarget,
    Math.floor((gridAvailableWidth - optionGap * 3) / 4)
  );

  return (
    <Modal
      testID="date-picker-modal"
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={() => handleCancelForOp(modalOpId)}>
      <SafeAreaView
        testID="date-picker-overlay"
        edges={['top', 'bottom']}
        style={[styles.overlay, { paddingHorizontal: overlayPaddingH, paddingVertical: isCompactViewport ? spacing.sm : spacing.lg }]}>
        <View
          testID="date-picker-dialog"
          style={[
            styles.dialog,
            {
              width: dialogWidth,
              paddingHorizontal: dialogPaddingH,
              paddingVertical: isCompactViewport ? spacing.md : spacing.lg,
              backgroundColor: theme.surface,
              borderColor: theme.border,
            },
          ]}>
          <ScrollView bounces={false} showsVerticalScrollIndicator={false} contentContainerStyle={styles.dialogScrollContent}>
            <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>
              {title}
            </Text>

            <Text style={[styles.previewText, { color: theme.textSecondary }]}>
              {draftDate ? `Đang chọn: ${formatVietnameseDateDisplay(draftDate)}` : 'Chưa chọn ngày'}
            </Text>

            {invalidDayNotice ? (
              <Text
                testID="date-picker-invalid-day-notice"
                accessibilityRole="alert"
                style={[styles.noticeText, { color: theme.dangerText }]}>
                {invalidDayNotice}
              </Text>
            ) : null}

            {mondayOnly ? (
              <Text style={[styles.hintText, { color: theme.textSecondary }]}>
                Chỉ chọn ngày Thứ Hai hợp lệ cho đầu tuần.
              </Text>
            ) : null}

            {/* Month & Year Navigation Header */}
            <View style={styles.navRow}>
              <Pressable
                testID="date-picker-prev-month"
                accessibilityRole="button"
                accessibilityLabel="Tháng trước"
                onPress={() => handleShiftMonth(-1)}
                style={[styles.navIconBtn, { borderColor: theme.border, backgroundColor: theme.surfaceSubtle }]}>
                <Text style={[styles.navIconText, { color: theme.textPrimary }]}>‹</Text>
              </Pressable>

              <View style={styles.navCenterGroup}>
                <Pressable
                  testID="date-picker-month-toggle"
                  accessibilityRole="button"
                  accessibilityLabel={`Chọn tháng, hiện tại tháng ${browseMonth}`}
                  onPress={() => setViewMode((m) => (m === 'months' ? 'days' : 'months'))}
                  style={[
                    styles.navPillBtn,
                    {
                      paddingHorizontal: isCompactViewport ? spacing.sm : spacing.md,
                      borderColor: viewMode === 'months' ? theme.primary : theme.border,
                      backgroundColor: viewMode === 'months' ? theme.brandSoft : theme.surfaceSubtle,
                    },
                  ]}>
                  <Text style={[styles.navPillText, { color: theme.textPrimary }]}>
                    Tháng {browseMonth}
                  </Text>
                </Pressable>

                <Pressable
                  testID="date-picker-year-toggle"
                  accessibilityRole="button"
                  accessibilityLabel={`Chọn năm, hiện tại năm ${browseYear}`}
                  onPress={() => setViewMode((m) => (m === 'years' ? 'days' : 'years'))}
                  style={[
                    styles.navPillBtn,
                    {
                      paddingHorizontal: isCompactViewport ? spacing.sm : spacing.md,
                      borderColor: viewMode === 'years' ? theme.primary : theme.border,
                      backgroundColor: viewMode === 'years' ? theme.brandSoft : theme.surfaceSubtle,
                    },
                  ]}>
                  <Text style={[styles.navPillText, { color: theme.textPrimary }]}>
                    {browseYear}
                  </Text>
                </Pressable>
              </View>

              <Pressable
                testID="date-picker-next-month"
                accessibilityRole="button"
                accessibilityLabel="Tháng sau"
                onPress={() => handleShiftMonth(1)}
                style={[styles.navIconBtn, { borderColor: theme.border, backgroundColor: theme.surfaceSubtle }]}>
                <Text style={[styles.navIconText, { color: theme.textPrimary }]}>›</Text>
              </Pressable>
            </View>

            {viewMode === 'days' ? (
              <View style={[styles.calendarWrap, { gap: cellGap }]}>
                <View style={[styles.weekHeaderRow, { gap: cellGap }]}>
                  {WEEKDAY_HEADERS.map((wd) => (
                    <View key={wd} style={[styles.weekHeaderCell, { width: dayCellSize }]}>
                      <Text style={[styles.weekHeaderText, { color: theme.textSecondary }]}>
                        {wd}
                      </Text>
                    </View>
                  ))}
                </View>

                <View style={[styles.daysRowsWrap, { gap: cellGap }]}>
                  {weekRows.map((row, rowIdx) => (
                    <View
                      key={`week-${rowIdx}`}
                      testID={`date-picker-week-row-${rowIdx}`}
                      style={[styles.weekRow, { gap: cellGap }]}>
                      {row.map((day, colIdx) => {
                        if (day === null) {
                          return (
                            <View
                              key={`empty-${rowIdx}-${colIdx}`}
                              style={[styles.dayCellSpacer, { width: dayCellSize }]}
                            />
                          );
                        }
                        const iso = formatDateOnly({ year: browseYear, month: browseMonth, day });
                        const enabled = isDaySelectable(iso);
                        const selected = draftDate === iso;
                        return (
                          <Pressable
                            key={iso}
                            testID={`date-picker-day-${iso}`}
                            accessibilityRole="button"
                            accessibilityLabel={`Chọn ngày ${formatVietnameseDateDisplay(iso)}`}
                            accessibilityState={{ disabled: !enabled, selected }}
                            disabled={!enabled}
                            onPress={() => handleSelectDay(day)}
                            style={[
                              styles.dayCellBtn,
                              {
                                width: dayCellSize,
                                backgroundColor: selected
                                  ? theme.primary
                                  : enabled
                                    ? theme.surfaceSubtle
                                    : 'transparent',
                                borderColor: selected ? theme.primary : theme.border,
                                opacity: enabled ? 1 : 0.35,
                              },
                            ]}>
                            <Text
                              style={[
                                styles.dayCellText,
                                {
                                  color: selected ? theme.textOnPrimary : theme.textPrimary,
                                  fontWeight: selected ? '700' : '400',
                                },
                              ]}>
                              {day}
                            </Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  ))}
                </View>
              </View>
            ) : null}

            {viewMode === 'months' ? (
              <View style={[styles.optionGrid, { gap: optionGap }]}>
                {Array.from({ length: 12 }, (_, idx) => idx + 1).map((month) => {
                  const selected = browseMonth === month;
                  return (
                    <Pressable
                      key={month}
                      testID={`date-picker-month-${month}`}
                      accessibilityRole="button"
                      accessibilityLabel={`Tháng ${month}`}
                      accessibilityState={{ selected }}
                      onPress={() => handleSelectMonth(month)}
                      style={[
                        styles.monthCellBtn,
                        {
                          width: monthCellWidth,
                          backgroundColor: selected ? theme.primary : theme.surfaceSubtle,
                          borderColor: selected ? theme.primary : theme.border,
                        },
                      ]}>
                      <Text
                        style={[
                          styles.optionText,
                          { color: selected ? theme.textOnPrimary : theme.textPrimary },
                        ]}>
                        Tháng {month}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            ) : null}

            {viewMode === 'years' ? (
              <ScrollView
                style={styles.yearsScroll}
                contentContainerStyle={[styles.optionGrid, { gap: optionGap }]}
                nestedScrollEnabled>
                {years.map((y) => {
                  const selected = browseYear === y;
                  return (
                    <Pressable
                      key={y}
                      testID={`date-picker-year-${y}`}
                      accessibilityRole="button"
                      accessibilityLabel={`Năm ${y}`}
                      accessibilityState={{ selected }}
                      onPress={() => handleSelectYear(y)}
                      style={[
                        styles.yearCellBtn,
                        {
                          width: yearCellWidth,
                          backgroundColor: selected ? theme.primary : theme.surfaceSubtle,
                          borderColor: selected ? theme.primary : theme.border,
                        },
                      ]}>
                      <Text
                        style={[
                          styles.optionText,
                          { color: selected ? theme.textOnPrimary : theme.textPrimary },
                        ]}>
                        {y}
                      </Text>
                    </Pressable>
                  );
                })}
                {hasUnboundedEarlierYears ? (
                  <Pressable
                    testID="date-picker-earlier-years-button"
                    accessibilityRole="button"
                    accessibilityLabel={`Xem thêm năm trước ${minYear}`}
                    onPress={() => setExtraEarlierYears((n) => n + 50)}
                    style={[
                      styles.earlierYearsBtn,
                      { borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                    ]}>
                    <Text style={[styles.optionText, { color: theme.primary }]}>
                      Xem thêm năm trước ({minYear - 1}…)
                    </Text>
                  </Pressable>
                ) : null}
              </ScrollView>
            ) : null}

            {/* Action Footer */}
            <View style={styles.footer}>
              {allowClear && onClear && (value || draftDate) ? (
                <Pressable
                  testID="date-picker-clear-button"
                  accessibilityRole="button"
                  accessibilityLabel="Xóa ngày đã chọn"
                  onPress={() => handleClearForOp(modalOpId)}
                  style={[styles.footerBtn, { borderColor: theme.dangerText }]}>
                  <Text style={[styles.footerBtnText, { color: theme.dangerText }]}>Xóa</Text>
                </Pressable>
              ) : null}

              <Pressable
                testID="date-picker-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy chọn ngày"
                onPress={() => handleCancelForOp(modalOpId)}
                style={[styles.footerBtn, { borderColor: theme.border }]}>
                <Text style={[styles.footerBtnText, { color: theme.textSecondary }]}>Hủy</Text>
              </Pressable>

              <Pressable
                testID="date-picker-confirm-button"
                accessibilityRole="button"
                accessibilityLabel="Xác nhận ngày đã chọn"
                accessibilityState={{ disabled: !canConfirm }}
                disabled={!canConfirm}
                onPress={() => handleConfirmForOp(modalOpId)}
                style={[
                  styles.footerBtn,
                  styles.confirmBtn,
                  { backgroundColor: theme.primary, borderColor: theme.primary, opacity: canConfirm ? 1 : 0.45 },
                ]}>
                <Text style={[styles.footerBtnText, { color: theme.textOnPrimary }]}>Xác nhận</Text>
              </Pressable>
            </View>
          </ScrollView>
        </View>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.55)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.lg,
  },
  dialog: {
    maxWidth: 420,
    maxHeight: '100%',
    borderRadius: radius.lg,
    borderWidth: 1,
  },
  dialogScrollContent: {
    gap: spacing.sm,
  },
  title: {
    ...typography.h3,
  },
  previewText: {
    ...typography.bodySmall,
  },
  noticeText: {
    ...typography.caption,
  },
  hintText: {
    ...typography.caption,
  },
  navRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  navIconBtn: {
    minWidth: layout.minimumTouchTarget,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  navIconText: {
    ...typography.h2,
  },
  navCenterGroup: {
    flex: 1,
    flexShrink: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
  },
  navPillBtn: {
    minHeight: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  navPillText: {
    ...typography.label,
  },
  calendarWrap: {
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  weekHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'flex-start',
  },
  weekHeaderCell: {
    alignItems: 'center',
    paddingVertical: spacing.xs,
  },
  weekHeaderText: {
    ...typography.caption,
    fontWeight: '600',
  },
  daysRowsWrap: {
    gap: spacing.xs,
  },
  weekRow: {
    flexDirection: 'row',
    justifyContent: 'flex-start',
  },
  dayCellSpacer: {
    minHeight: layout.minimumTouchTarget,
  },
  dayCellBtn: {
    minWidth: layout.minimumTouchTarget,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dayCellText: {
    ...typography.bodySmall,
  },
  yearsScroll: {
    maxHeight: 240,
  },
  optionGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    paddingVertical: spacing.xs,
  },
  monthCellBtn: {
    minWidth: layout.minimumTouchTarget,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  yearCellBtn: {
    minWidth: layout.minimumTouchTarget,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  earlierYearsBtn: {
    width: '100%',
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  optionText: {
    ...typography.label,
  },
  footer: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  footerBtn: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmBtn: {
    paddingHorizontal: spacing.lg,
  },
  footerBtnText: {
    ...typography.label,
  },
});
