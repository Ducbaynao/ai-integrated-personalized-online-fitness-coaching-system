import React from 'react';
import * as ReactNative from 'react-native';
import { Platform, StyleSheet } from 'react-native';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { DateInputField } from '@/components/ui/DateInputField';
import { DatePickerModal } from '@/components/ui/DatePickerModal';
import { LocalDateTimeInputField } from '@/components/ui/LocalDateTimeInputField';
import { layout } from '@/design-system/tokens/spacing';
import {
  formatDateOnly,
  formatVietnameseDateDisplay,
  getCalendarGridDays,
  getTodayDateOnly,
  isLeapYear,
  isMondayDateOnly,
  joinLocalDateTime,
  localDateRangeBoundaryIso,
  parseDateOnly,
  replaceDateInIsoTimestamp,
  splitLocalDateTime,
} from '@/features/shared/dateInputUtils';

function pickDateInField(
  root: renderer.ReactTestInstance,
  triggerTestID: string,
  isoDate: string
) {
  const [yStr, mStr] = isoDate.split('-');
  renderer.act(() => {
    root.findByProps({ testID: triggerTestID }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-year-${Number(yStr)}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-month-${Number(mStr)}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-day-${isoDate}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
  });
}

describe('P7-003 / P7-013 / P7-009 shared date inputs and calendar utilities', () => {
  afterEach(() => {
    moment.now = () => Date.now();
  });

  it('validates leap years, non-existent days, and formats Vietnamese DD/MM/YYYY without UTC drift', () => {
    expect(isLeapYear(2024)).toBe(true);
    expect(isLeapYear(2000)).toBe(true);
    expect(isLeapYear(1900)).toBe(false);
    expect(isLeapYear(2026)).toBe(false);

    expect(parseDateOnly('2024-02-29')).toEqual({ year: 2024, month: 2, day: 29 });
    expect(parseDateOnly('2026-02-29')).toBeNull();
    expect(parseDateOnly('2026-04-31')).toBeNull();
    expect(parseDateOnly('1995-06-15')).toEqual({ year: 1995, month: 6, day: 15 });

    expect(formatDateOnly({ year: 1995, month: 6, day: 5 })).toBe('1995-06-05');
    expect(formatVietnameseDateDisplay('1995-06-15')).toBe('15/06/1995');
    expect(formatVietnameseDateDisplay('')).toBe('');
    expect(formatVietnameseDateDisplay(null)).toBe('');

    // Grid and Monday check remain identical regardless of host/target timezone across midnight
    expect(isMondayDateOnly('2026-10-12')).toBe(true);
    expect(isMondayDateOnly('2026-10-13')).toBe(false);
    const feb2024 = getCalendarGridDays(2024, 2);
    // 2024-02-01 was Thursday -> Mon(0), Tue(1), Wed(2) are null, index 3 is day 1
    expect(feb2024.slice(0, 4)).toEqual([null, null, null, 1]);
    expect(feb2024.filter((d) => d !== null)).toHaveLength(29);

    // Date-only "today" resolves accurately in negative & positive UTC offsets
    moment.now = () => Date.parse('2026-11-26T02:30:00Z');
    expect(getTodayDateOnly('Asia/Ho_Chi_Minh')).toBe('2026-11-26');
    expect(getTodayDateOnly('America/New_York')).toBe('2026-11-25');
    expect(getTodayDateOnly('Pacific/Honolulu')).toBe('2026-11-25');
  });

  it('supports far-past year selection (DOB), preserves empty state on cancel, and clears back to empty', () => {
    const onChange = jest.fn();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <DateInputField
          testID="dob-field"
          clearTestID="dob-field-clear"
          label="Ngày sinh"
          value=""
          onChange={onChange}
          allowClear
          maxDate="2026-11-26"
          initialBrowseDate="1995-06-15"
          yearRange={{ min: 1920, max: 2026 }}
        />
      );
    });

    const trigger = tree.root.findByProps({ testID: 'dob-field-trigger' });
    expect(trigger.props.accessibilityValue.text).toBe('Chưa chọn');
    expect(trigger.props.accessibilityRole).toBe('button');

    // Open picker, browse & pick a date, then press Cancel -> must NOT invoke onChange or invent a default
    renderer.act(() => {
      trigger.props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-day-1995-06-15' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });
    expect(onChange).not.toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'date-picker-modal' })).toHaveLength(0);

    // Pick leap-day DOB from far past year 1992 and confirm -> emits exact YYYY-MM-DD
    pickDateInField(tree.root, 'dob-field-trigger', '1992-02-29');
    expect(onChange).toHaveBeenCalledWith('1992-02-29');

    // Re-render with selected value, verify Vietnamese display DD/MM/YYYY and touch target >= 44
    renderer.act(() => {
      tree.update(
        <DateInputField
          testID="dob-field"
          clearTestID="dob-field-clear"
          label="Ngày sinh"
          value="1992-02-29"
          onChange={onChange}
          allowClear
          maxDate="2026-11-26"
          initialBrowseDate="1995-06-15"
          yearRange={{ min: 1920, max: 2026 }}
        />
      );
    });
    expect(tree.root.findByProps({ testID: 'dob-field-trigger' }).props.accessibilityValue.text).toBe('29/02/1992');

    const clearBtn = tree.root.findByProps({ testID: 'dob-field-clear' });
    const clearStyle = StyleSheet.flatten(clearBtn.props.style({ pressed: false }));
    expect(clearStyle.minHeight).toBeGreaterThanOrEqual(layout.minimumTouchTarget);
    expect(clearStyle.minWidth).toBeGreaterThanOrEqual(layout.minimumTouchTarget);

    // Clearing optional field resets to ''
    renderer.act(() => {
      clearBtn.props.onPress();
    });
    expect(onChange).toHaveBeenLastCalledWith('');
  });

  it('enforces minDate/maxDate and mondayOnly constraints inside calendar picker', () => {
    const onChange = jest.fn();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <DateInputField
          testID="monday-field"
          label="Ngày thứ Hai"
          value="2026-10-12"
          onChange={onChange}
          minDate="2026-10-05"
          maxDate="2026-10-20"
          mondayOnly
        />
      );
    });

    renderer.act(() => {
      tree.root.findByProps({ testID: 'monday-field-trigger' }).props.onPress();
    });

    // Tuesday 2026-10-13 disabled because mondayOnly=true
    expect(tree.root.findByProps({ testID: 'date-picker-day-2026-10-13' }).props.disabled).toBe(true);
    // Monday 2026-10-19 enabled within [minDate, maxDate]
    expect(tree.root.findByProps({ testID: 'date-picker-day-2026-10-19' }).props.disabled).toBe(false);
    // Monday 2026-10-26 disabled because > maxDate
    expect(tree.root.findByProps({ testID: 'date-picker-day-2026-10-26' }).props.disabled).toBe(true);
    // Minimum touch targets on calendar buttons
    const dayStyle = StyleSheet.flatten(
      tree.root.findByProps({ testID: 'date-picker-day-2026-10-19' }).props.style
    );
    expect(dayStyle.minHeight).toBeGreaterThanOrEqual(layout.minimumTouchTarget);
  });

  it('closes open picker and ignores stale callbacks when sessionKey changes while open', () => {
    const onChangeSession = jest.fn();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <DateInputField
          testID="session-field"
          label="Ngày"
          value="2026-10-12"
          onChange={onChangeSession}
          sessionKey="student-A"
        />
      );
    });

    renderer.act(() => {
      tree.root.findByProps({ testID: 'session-field-trigger' }).props.onPress();
    });
    const staleConfirm = tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress;
    expect(tree.root.findByProps({ testID: 'date-picker-modal' })).toBeTruthy();

    // Switch active session/route/actor while modal was open
    renderer.act(() => {
      tree.update(
        <DateInputField
          testID="session-field"
          label="Ngày"
          value=""
          onChange={onChangeSession}
          sessionKey="student-B"
        />
      );
    });

    // Modal is closed for session B and invoking captured stale callback does nothing
    expect(tree.root.findAllByProps({ testID: 'date-picker-modal' })).toHaveLength(0);
    renderer.act(() => {
      staleConfirm();
    });
    expect(onChangeSession).not.toHaveBeenCalled();
  });

  it('renders cleanly on both android and web platforms and keeps LocalDateTimeInputField semantics', () => {
    const originalOs = Platform.OS;
    try {
      for (const os of ['android', 'web'] as const) {
        Platform.OS = os;
        const onChange = jest.fn();
        let tree!: renderer.ReactTestRenderer;
        renderer.act(() => {
          tree = renderer.create(
            <LocalDateTimeInputField
              label="Bắt đầu"
              value="2026-10-25T09:30"
              onChange={onChange}
              dateTestID="ldt-date"
              timeTestID="ldt-time"
            />
          );
        });
        expect(tree.root.findByProps({ testID: 'ldt-date-trigger' }).props.accessibilityValue.text).toBe('25/10/2026');
        expect(tree.root.findByProps({ testID: 'ldt-time' }).props.value).toBe('09:30');

        pickDateInField(tree.root, 'ldt-date-trigger', '2026-10-26');
        expect(onChange).toHaveBeenCalledWith('2026-10-26T09:30');

        renderer.act(() => {
          tree.root.findByProps({ testID: 'ldt-time' }).props.onChangeText('14:15');
        });
        expect(onChange).toHaveBeenCalledWith('2026-10-25T14:15');
      }
    } finally {
      Platform.OS = originalOs;
    }

    expect(splitLocalDateTime('2026-10-25T09:30')).toEqual({ date: '2026-10-25', time: '09:30' });
    expect(joinLocalDateTime('2026-10-25', '')).toBe('2026-10-25T');
    expect(joinLocalDateTime('', '')).toBe('');
    expect(localDateRangeBoundaryIso('2026-11-26', 'start', 'Asia/Ho_Chi_Minh')).toBe('2026-11-25T17:00:00.000Z');
  });

  it('P2-1: ignores stale confirm/clear/cancel callbacks after close+reopen (same sessionKey), disabled, and unmount', () => {
    const onChange = jest.fn();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <DateInputField
          testID="op-field"
          label="Ngày"
          value="2026-10-12"
          onChange={onChange}
          allowClear
          sessionKey="student-A"
        />
      );
    });

    // Operation 1: open picker, capture callbacks exposed to DatePickerModal
    renderer.act(() => {
      tree.root.findByProps({ testID: 'op-field-trigger' }).props.onPress();
    });
    const modal1 = tree.root.findByType(DatePickerModal);
    const staleConfirm = modal1.props.onConfirm;
    const staleClear = modal1.props.onClear;
    const staleCancel = modal1.props.onCancel;

    // Cancel operation 1 and immediately reopen operation 2 with identical sessionKey
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'op-field-trigger' }).props.onPress();
    });
    expect(tree.root.findByProps({ testID: 'date-picker-modal' })).toBeTruthy();
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-day-2026-10-19' }).props.onPress();
    });

    // Invoking any stale callback from operation 1 must NOT mutate, close, or trigger onChange
    renderer.act(() => {
      staleConfirm('2026-10-12');
      staleClear?.();
      staleCancel();
    });
    expect(onChange).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ testID: 'date-picker-modal' })).toBeTruthy();

    // Confirming current operation 2 emits the newly picked date
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
    });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('2026-10-19');

    // Reopen operation 3, toggle disabled=true while open, verify modal closes and captured callbacks stay inert
    onChange.mockClear();
    renderer.act(() => {
      tree.root.findByProps({ testID: 'op-field-trigger' }).props.onPress();
    });
    const modal3 = tree.root.findByType(DatePickerModal);
    const op3Confirm = modal3.props.onConfirm;
    const op3Clear = modal3.props.onClear;
    renderer.act(() => {
      tree.update(
        <DateInputField
          testID="op-field"
          label="Ngày"
          value="2026-10-12"
          onChange={onChange}
          allowClear
          disabled
          sessionKey="student-A"
        />
      );
    });
    expect(tree.root.findAllByProps({ testID: 'date-picker-modal' })).toHaveLength(0);
    renderer.act(() => {
      op3Confirm('2026-10-20');
      op3Clear?.();
    });
    expect(onChange).not.toHaveBeenCalled();

    // Re-enable, open operation 4, unmount while open -> callbacks from operation 4 stay inert
    renderer.act(() => {
      tree.update(
        <DateInputField
          testID="op-field"
          label="Ngày"
          value="2026-10-12"
          onChange={onChange}
          allowClear
          disabled={false}
          sessionKey="student-A"
        />
      );
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'op-field-trigger' }).props.onPress();
    });
    const modal4 = tree.root.findByType(DatePickerModal);
    const op4Confirm = modal4.props.onConfirm;
    const op4Clear = modal4.props.onClear;
    const op4Cancel = modal4.props.onCancel;
    renderer.act(() => {
      tree.unmount();
    });
    renderer.act(() => {
      op4Confirm('2026-10-21');
      op4Clear?.();
      op4Cancel();
    });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('P2-2: preserves valid pre-1920 DOB on month change and handles non-existent day without silent clamp', () => {
    const onChange = jest.fn();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <DateInputField
          testID="legacy-dob"
          label="Ngày sinh"
          value="1919-06-15"
          onChange={onChange}
          allowClear
          maxDate="2026-11-26"
        />
      );
    });

    renderer.act(() => {
      tree.root.findByProps({ testID: 'legacy-dob-trigger' }).props.onPress();
    });

    // Switch to month 7 and confirm -> must keep year 1919 -> 1919-07-15 (not clamped to 1920)
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-month-7' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
    });
    expect(onChange).toHaveBeenCalledWith('1919-07-15');

    // Even if a caller passes browse hint yearRange.min=1920, existing valid 1919-06-15 stays selectable without clamping
    const onHintChange = jest.fn();
    let hintTree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      hintTree = renderer.create(
        <DateInputField
          testID="legacy-dob-hint"
          label="Ngày sinh"
          value="1919-06-15"
          onChange={onHintChange}
          maxDate="2026-11-26"
          yearRange={{ min: 1920, max: 2026 }}
        />
      );
    });
    renderer.act(() => {
      hintTree.root.findByProps({ testID: 'legacy-dob-hint-trigger' }).props.onPress();
    });
    expect(hintTree.root.findByProps({ testID: 'date-picker-day-1919-06-16' })).toBeTruthy();
    renderer.act(() => {
      hintTree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });

    // Year grid conveniently reaches pre-1920 years without imposing a 1920 floor
    renderer.act(() => {
      tree.root.findByProps({ testID: 'legacy-dob-trigger' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress();
    });
    expect(tree.root.findByProps({ testID: 'date-picker-year-1919' })).toBeTruthy();
    renderer.act(() => {
      tree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });

    // Non-existent day transition: 2024-01-31 -> switch to month 2 must NOT silently clamp to 2024-02-29
    const onMonthChange = jest.fn();
    let invalidDayTree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      invalidDayTree = renderer.create(
        <DateInputField
          testID="invalid-day-field"
          label="Ngày"
          value="2024-01-31"
          onChange={onMonthChange}
        />
      );
    });
    renderer.act(() => {
      invalidDayTree.root.findByProps({ testID: 'invalid-day-field-trigger' }).props.onPress();
    });
    renderer.act(() => {
      invalidDayTree.root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress();
    });
    renderer.act(() => {
      invalidDayTree.root.findByProps({ testID: 'date-picker-month-2' }).props.onPress();
    });
    expect(invalidDayTree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.disabled).toBe(true);
    expect(JSON.stringify(invalidDayTree.toJSON())).toContain('không tồn tại');
    renderer.act(() => {
      invalidDayTree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });
    expect(onMonthChange).not.toHaveBeenCalled();
  });

  it('P2-3: localDateRangeBoundaryIso returns exclusive next-day startOf(day) for end boundary including DST', () => {
    // 2026-11-26 end (exclusive) in +07:00 is 2026-11-27 00:00:00+07:00 = 2026-11-26T17:00:00.000Z
    expect(localDateRangeBoundaryIso('2026-11-26', 'end', 'Asia/Ho_Chi_Minh')).toBe('2026-11-26T17:00:00.000Z');
    // America/New_York DST spring-forward on 2026-03-08: 2026-03-09 00:00:00 EDT (-04:00) = 2026-03-09T04:00:00.000Z
    expect(localDateRangeBoundaryIso('2026-03-08', 'start', 'America/New_York')).toBe('2026-03-08T05:00:00.000Z');
    expect(localDateRangeBoundaryIso('2026-03-08', 'end', 'America/New_York')).toBe('2026-03-09T04:00:00.000Z');

    // replaceDateInIsoTimestamp keeps unchanged instant verbatim and preserves time-of-day across DST / year boundary
    const nyWinter = '2026-02-28T15:30:45.500Z'; // 10:30:45.500 EST (-05:00)
    expect(replaceDateInIsoTimestamp(nyWinter, '2026-02-28', 'America/New_York')).toBe(nyWinter);
    // Crossing into EDT (2026-03-09, -04:00): local wall-clock 10:30:45.500 -> 14:30:45.500Z
    expect(replaceDateInIsoTimestamp(nyWinter, '2026-03-09', 'America/New_York')).toBe('2026-03-09T14:30:45.500Z');
    // Year boundary in Asia/Ho_Chi_Minh (+07:00): 2026-12-31T18:10:00.000Z is 2027-01-01 01:10:00+07:00
    expect(replaceDateInIsoTimestamp('2026-12-31T18:10:00.000Z', '2026-12-31', 'Asia/Ho_Chi_Minh')).toBe(
      '2026-12-30T18:10:00.000Z'
    );
    // DST spring-forward gap in America/New_York: 2026-03-07T07:30:45.123Z is local 02:30:45.123 EST;
    // changing date to 2026-03-08 (where 02:30 does not exist) must return null rather than normalizing to 03:30.
    expect(replaceDateInIsoTimestamp('2026-03-07T07:30:45.123Z', '2026-03-08', 'America/New_York')).toBeNull();
    // Moving past the gap to 2026-03-09 (-04:00) preserves 02:30:45.123 accurately
    expect(replaceDateInIsoTimestamp('2026-03-07T07:30:45.123Z', '2026-03-09', 'America/New_York')).toBe(
      '2026-03-09T06:30:45.123Z'
    );
  });

  it('P2-4: renders 7-column day grid with >= 44px width and height and zero horizontal overflow at 360px viewport', () => {
    const spy = jest.spyOn(ReactNative.Dimensions, 'get').mockReturnValue({
      width: 360,
      height: 640,
      scale: 2,
      fontScale: 1,
    });
    try {
      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <DatePickerModal
            visible
            title="Chọn ngày"
            value="2026-10-15"
            onConfirm={jest.fn()}
            onCancel={jest.fn()}
          />
        );
      });

      const dialog = tree.root.findByProps({ testID: 'date-picker-dialog' });
      const overlay = tree.root.findByProps({ testID: 'date-picker-overlay' });
      const dayCell = tree.root.findByProps({ testID: 'date-picker-day-2026-10-15' });
      const dayRows = tree.root.findAll((node) => node.props.testID?.startsWith('date-picker-week-row-'));

      const overlayStyle = StyleSheet.flatten(overlay.props.style);
      const dialogStyle = StyleSheet.flatten(dialog.props.style);
      const dayStyle = StyleSheet.flatten(dayCell.props.style);

      expect(dayRows.length).toBeGreaterThanOrEqual(4);
      expect(typeof dayStyle.width).toBe('number');
      expect(dayStyle.width).toBeGreaterThanOrEqual(layout.minimumTouchTarget);
      expect(dayStyle.minHeight).toBeGreaterThanOrEqual(layout.minimumTouchTarget);

      const overlayHorizontal =
        typeof overlayStyle.paddingHorizontal === 'number'
          ? overlayStyle.paddingHorizontal * 2
          : (Number(overlayStyle.padding) || 0) * 2;
      const dialogHorizontal =
        typeof dialogStyle.paddingHorizontal === 'number'
          ? dialogStyle.paddingHorizontal * 2
          : (Number(dialogStyle.padding) || 0) * 2;
      const dialogBorder = (Number(dialogStyle.borderWidth) || 0) * 2;
      const availableInnerWidth = 360 - overlayHorizontal - dialogHorizontal - dialogBorder;
      const rowGap = Number(StyleSheet.flatten(dayRows[0].props.style).gap) || 0;
      const requiredRowWidth = 7 * Number(dayStyle.width) + 6 * rowGap;

      expect(requiredRowWidth).toBeLessThanOrEqual(availableInnerWidth);
      expect(overlayHorizontal + dialogHorizontal + dialogBorder + requiredRowWidth).toBeLessThanOrEqual(360);
    } finally {
      spy.mockRestore();
    }
  });
});
