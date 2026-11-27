/* eslint-disable no-extend-native -- test-only: temporarily replaces Date locale methods to simulate a device timezone, and restores them. */
/**
 * Test helper: make the JS runtime behave as if the phone were set to `zone`.
 *
 * Jest runs test code in a sandbox whose `process.env` is a copy, so assigning `process.env.TZ` inside a
 * test does not change the real timezone. Instead, every API a screen could use to format a Date with the
 * *device* timezone is routed through that zone: `Intl.DateTimeFormat` without an explicit `timeZone`
 * and `Date.prototype.toLocale{,Date,Time}String`. Calls that name a `timeZone` (what the app must do)
 * are untouched, so a screen that formats with an explicit zone renders identically on every simulated
 * device, while one that relies on the device default changes.
 *
 * It does not patch `Date#getHours()`-style getters or `moment()` local time; screens under test must not
 * use those for display.
 */
type DateTimeFormatCtor = typeof Intl.DateTimeFormat;
type Kind = 'any' | 'date' | 'time';

const DATE_KEYS = ['weekday', 'era', 'year', 'month', 'day'] as const;
const TIME_KEYS = ['dayPeriod', 'hour', 'minute', 'second', 'fractionalSecondDigits'] as const;

function withSpecDefaults(kind: Kind, options: Intl.DateTimeFormatOptions = {}): Intl.DateTimeFormatOptions {
  const bag = options as Record<string, unknown>;
  if (bag.dateStyle !== undefined || bag.timeStyle !== undefined) return options;
  const hasDate = DATE_KEYS.some((key) => bag[key] !== undefined);
  const hasTime = TIME_KEYS.some((key) => bag[key] !== undefined);
  const needDefaults = kind === 'any' ? !hasDate && !hasTime : kind === 'date' ? !hasDate : !hasTime;
  if (!needDefaults) return options;
  return {
    ...options,
    ...(kind !== 'time' ? { year: 'numeric', month: 'numeric', day: 'numeric' } : {}),
    ...(kind !== 'date' ? { hour: 'numeric', minute: 'numeric', second: 'numeric' } : {}),
  } as Intl.DateTimeFormatOptions;
}

/** Returns a function that restores the real runtime behaviour. */
export function simulateDeviceTimezone(zone: string): () => void {
  const RealDateTimeFormat = Intl.DateTimeFormat;
  const realToLocaleString = Date.prototype.toLocaleString;
  const realToLocaleDateString = Date.prototype.toLocaleDateString;
  const realToLocaleTimeString = Date.prototype.toLocaleTimeString;

  const DeviceDateTimeFormat = function DeviceDateTimeFormat(
    locales?: Intl.LocalesArgument,
    options?: Intl.DateTimeFormatOptions,
  ) {
    return new RealDateTimeFormat(locales, { timeZone: zone, ...(options ?? {}) });
  } as unknown as DateTimeFormatCtor;
  Object.defineProperty(DeviceDateTimeFormat, 'prototype', { value: RealDateTimeFormat.prototype });
  DeviceDateTimeFormat.supportedLocalesOf = RealDateTimeFormat.supportedLocalesOf.bind(RealDateTimeFormat);

  const patch = (kind: Kind) => function patched(this: Date, locales?: Intl.LocalesArgument, options?: Intl.DateTimeFormatOptions) {
    if (Number.isNaN(this.getTime())) return 'Invalid Date';
    return new DeviceDateTimeFormat(locales, withSpecDefaults(kind, options)).format(this);
  };

  Object.defineProperty(Intl, 'DateTimeFormat', { value: DeviceDateTimeFormat, configurable: true, writable: true });
  Object.defineProperty(Date.prototype, 'toLocaleString', { value: patch('any'), configurable: true, writable: true });
  Object.defineProperty(Date.prototype, 'toLocaleDateString', { value: patch('date'), configurable: true, writable: true });
  Object.defineProperty(Date.prototype, 'toLocaleTimeString', { value: patch('time'), configurable: true, writable: true });

  return () => {
    Object.defineProperty(Intl, 'DateTimeFormat', { value: RealDateTimeFormat, configurable: true, writable: true });
    Object.defineProperty(Date.prototype, 'toLocaleString', { value: realToLocaleString, configurable: true, writable: true });
    Object.defineProperty(Date.prototype, 'toLocaleDateString', { value: realToLocaleDateString, configurable: true, writable: true });
    Object.defineProperty(Date.prototype, 'toLocaleTimeString', { value: realToLocaleTimeString, configurable: true, writable: true });
  };
}
