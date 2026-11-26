import moment from 'moment-timezone';
import { BACKEND_ZONE_IDS } from './backendTimezoneCatalog';

export const DEFAULT_APP_TIMEZONE = 'Asia/Ho_Chi_Minh';

export interface FriendlyTimezoneOption {
  timezone: string;
  label: string;
  searchText: string;
}

const CURATED_TIMEZONE_ENTRIES: readonly { timezone: string; label: string; keywords: string }[] = [
  { timezone: 'Asia/Ho_Chi_Minh', label: 'Giờ Việt Nam', keywords: 'viet nam ho chi minh hcm ha noi' },
  { timezone: 'Asia/Bangkok', label: 'Giờ Thái Lan (Bangkok)', keywords: 'thai lan thailand bangkok' },
  { timezone: 'Asia/Singapore', label: 'Giờ Singapore', keywords: 'singapore' },
  { timezone: 'Asia/Tokyo', label: 'Giờ Nhật Bản (Tokyo)', keywords: 'nhat ban japan tokyo' },
  { timezone: 'Asia/Seoul', label: 'Giờ Hàn Quốc (Seoul)', keywords: 'han quoc korea seoul' },
  { timezone: 'Asia/Taipei', label: 'Giờ Đài Loan (Đài Bắc)', keywords: 'dai loan taiwan dai bac taipei' },
  { timezone: 'Asia/Hong_Kong', label: 'Giờ Hồng Kông', keywords: 'hong kong' },
  { timezone: 'Asia/Shanghai', label: 'Giờ Trung Quốc (Bắc Kinh)', keywords: 'trung quoc china bac kinh shanghai beijing' },
  { timezone: 'Asia/Jakarta', label: 'Giờ Tây Indonesia (Jakarta)', keywords: 'indonesia jakarta' },
  { timezone: 'Asia/Manila', label: 'Giờ Philippines (Manila)', keywords: 'philippines manila' },
  { timezone: 'Australia/Sydney', label: 'Giờ Đông Úc (Sydney)', keywords: 'uc australia sydney' },
  { timezone: 'Australia/Melbourne', label: 'Giờ Đông Úc (Melbourne)', keywords: 'uc australia melbourne' },
  { timezone: 'Europe/London', label: 'Giờ Vương quốc Anh (London)', keywords: 'anh uk united kingdom london' },
  { timezone: 'Europe/Paris', label: 'Giờ Trung Âu (Paris)', keywords: 'phap france paris chau au' },
  { timezone: 'Europe/Berlin', label: 'Giờ Trung Âu (Berlin)', keywords: 'duc germany berlin chau au' },
  { timezone: 'America/New_York', label: 'Giờ Miền Đông Hoa Kỳ (New York)', keywords: 'my hoa ky usa new york mien dong' },
  { timezone: 'America/Chicago', label: 'Giờ Miền Trung Hoa Kỳ (Chicago)', keywords: 'my hoa ky usa chicago mien trung' },
  { timezone: 'America/Denver', label: 'Giờ Miền Núi Hoa Kỳ (Denver)', keywords: 'my hoa ky usa denver mien nui' },
  { timezone: 'America/Los_Angeles', label: 'Giờ Thái Bình Dương Hoa Kỳ (Los Angeles)', keywords: 'my hoa ky usa los angeles california' },
  { timezone: 'America/Toronto', label: 'Giờ Miền Đông Canada (Toronto)', keywords: 'canada toronto' },
  { timezone: 'UTC', label: 'Giờ chuẩn quốc tế (UTC)', keywords: 'utc quoc te chuan' },
  { timezone: 'Etc/UTC', label: 'Giờ chuẩn quốc tế (UTC) · dạng đầy đủ', keywords: 'utc quoc te chuan' },
] as const;

const LEGACY_ALIAS_LABELS: Record<string, { label: string; keywords: string }> = {
  'Asia/Saigon': { label: 'Giờ Việt Nam · Sài Gòn (tên cũ)', keywords: 'viet nam sai gon saigon ho chi minh' },
  'Asia/Calcutta': { label: 'Khung giờ Calcutta · Ấn Độ (tên cũ)', keywords: 'an do india calcutta kolkata' },
  'Asia/Kolkata': { label: 'Khung giờ Kolkata (Ấn Độ)', keywords: 'an do india kolkata' },
  'Asia/Katmandu': { label: 'Khung giờ Kathmandu · Nepal (tên cũ)', keywords: 'nepal katmandu kathmandu' },
  'Asia/Kathmandu': { label: 'Khung giờ Kathmandu (Nepal)', keywords: 'nepal kathmandu' },
  'Asia/Rangoon': { label: 'Khung giờ Yangon · Myanmar (tên cũ)', keywords: 'myanmar rangoon yangon' },
  'Asia/Yangon': { label: 'Khung giờ Yangon (Myanmar)', keywords: 'myanmar yangon' },
  'Europe/Kiev': { label: 'Khung giờ Kiev · Ukraina (tên cũ)', keywords: 'ukraina ukraine kiev kyiv' },
  'Europe/Kyiv': { label: 'Khung giờ Kyiv (Ukraina)', keywords: 'ukraina ukraine kyiv' },
  CET: { label: 'Giờ Trung Âu tiêu chuẩn (tên cũ)', keywords: 'chau au central europe' },
  EET: { label: 'Giờ Đông Âu tiêu chuẩn (tên cũ)', keywords: 'chau au eastern europe' },
  MET: { label: 'Giờ Trung Âu lục địa (tên cũ)', keywords: 'chau au middle europe' },
  WET: { label: 'Giờ Tây Âu tiêu chuẩn (tên cũ)', keywords: 'chau au western europe' },
  EST: { label: 'Giờ Miền Đông Bắc Mỹ cố định (tên cũ)', keywords: 'my bac my eastern standard' },
  MST: { label: 'Giờ Miền Núi Bắc Mỹ cố định (tên cũ)', keywords: 'my bac my mountain standard' },
  HST: { label: 'Giờ Hawaii cố định (tên cũ)', keywords: 'hawaii my' },
  EST5EDT: { label: 'Giờ Miền Đông Bắc Mỹ (có giờ mùa hè)', keywords: 'my bac my eastern dst' },
  CST6CDT: { label: 'Giờ Miền Trung Bắc Mỹ (có giờ mùa hè)', keywords: 'my bac my central dst' },
  MST7MDT: { label: 'Giờ Miền Núi Bắc Mỹ (có giờ mùa hè)', keywords: 'my bac my mountain dst' },
  PST8PDT: { label: 'Giờ Thái Bình Dương Bắc Mỹ (có giờ mùa hè)', keywords: 'my bac my pacific dst' },
  GMT: { label: 'Giờ chuẩn Greenwich (GMT)', keywords: 'greenwich gmt utc anh' },
  'GMT+0': { label: 'Giờ chuẩn Greenwich (+0 · tên cũ)', keywords: 'greenwich gmt' },
  'GMT-0': { label: 'Giờ chuẩn Greenwich (-0 · tên cũ)', keywords: 'greenwich gmt' },
  GMT0: { label: 'Giờ chuẩn Greenwich (0 · tên cũ)', keywords: 'greenwich gmt' },
  'Etc/GMT': { label: 'Giờ chuẩn Greenwich (chuẩn hệ thống)', keywords: 'greenwich gmt' },
  'Etc/GMT+0': { label: 'Giờ chuẩn Greenwich (nhóm +0)', keywords: 'greenwich gmt' },
  'Etc/GMT-0': { label: 'Giờ chuẩn Greenwich (nhóm -0)', keywords: 'greenwich gmt' },
  'Etc/GMT0': { label: 'Giờ chuẩn Greenwich (nhóm 0)', keywords: 'greenwich gmt' },
  'Etc/Greenwich': { label: 'Giờ chuẩn Greenwich (dạng đầy đủ)', keywords: 'greenwich gmt' },
  'Etc/UCT': { label: 'Giờ phối hợp quốc tế (dạng đầy đủ)', keywords: 'uct utc quoc te' },
  'Etc/Universal': { label: 'Giờ phổ quát quốc tế (dạng đầy đủ)', keywords: 'utc quoc te universal' },
  'Etc/Zulu': { label: 'Giờ chuẩn quốc tế Zulu (dạng đầy đủ)', keywords: 'utc zulu quoc te' },
  Greenwich: { label: 'Giờ chuẩn Greenwich (tên cũ)', keywords: 'greenwich gmt' },
  UCT: { label: 'Giờ phối hợp quốc tế (tên cũ)', keywords: 'uct utc quoc te' },
  Universal: { label: 'Giờ phổ quát quốc tế (tên cũ)', keywords: 'utc universal quoc te' },
  Zulu: { label: 'Giờ chuẩn quốc tế Zulu (tên cũ)', keywords: 'utc zulu quoc te' },
  Cuba: { label: 'Khung giờ Cuba (tên cũ)', keywords: 'cuba' },
  Egypt: { label: 'Khung giờ Ai Cập (tên cũ)', keywords: 'ai cap egypt' },
  Eire: { label: 'Khung giờ Ireland (tên cũ)', keywords: 'ireland eire' },
  GB: { label: 'Khung giờ Vương quốc Anh (tên cũ)', keywords: 'anh uk london' },
  'GB-Eire': { label: 'Khung giờ Anh và Ireland (tên cũ)', keywords: 'anh ireland uk' },
  Hongkong: { label: 'Giờ Hồng Kông (tên cũ)', keywords: 'hong kong' },
  Iceland: { label: 'Khung giờ Iceland (tên cũ)', keywords: 'iceland' },
  Iran: { label: 'Khung giờ Iran (tên cũ)', keywords: 'iran' },
  Israel: { label: 'Khung giờ Israel (tên cũ)', keywords: 'israel' },
  Jamaica: { label: 'Khung giờ Jamaica (tên cũ)', keywords: 'jamaica' },
  Japan: { label: 'Giờ Nhật Bản (tên cũ)', keywords: 'nhat ban japan' },
  Kwajalein: { label: 'Khung giờ Kwajalein (tên cũ)', keywords: 'kwajalein' },
  Libya: { label: 'Khung giờ Libya (tên cũ)', keywords: 'libya' },
  Navajo: { label: 'Khung giờ Vùng Navajo (tên cũ)', keywords: 'navajo my hoa ky' },
  NZ: { label: 'Khung giờ New Zealand (tên cũ)', keywords: 'new zealand' },
  'NZ-CHAT': { label: 'Khung giờ Quần đảo Chatham (tên cũ)', keywords: 'new zealand chatham' },
  Poland: { label: 'Khung giờ Ba Lan (tên cũ)', keywords: 'ba lan poland' },
  Portugal: { label: 'Khung giờ Bồ Đào Nha (tên cũ)', keywords: 'bo dao nha portugal' },
  PRC: { label: 'Giờ Trung Quốc đại lục (tên cũ)', keywords: 'trung quoc china' },
  ROC: { label: 'Giờ Đài Loan (tên cũ)', keywords: 'dai loan taiwan' },
  ROK: { label: 'Giờ Hàn Quốc (tên cũ)', keywords: 'han quoc korea' },
  Singapore: { label: 'Giờ Singapore (tên cũ)', keywords: 'singapore' },
  Turkey: { label: 'Khung giờ Thổ Nhĩ Kỳ (tên cũ)', keywords: 'tho nhi ky turkey' },
  'W-SU': { label: 'Khung giờ Tây Nga · Moskva (tên cũ)', keywords: 'nga russia moscow moskva' },
};

const REGION_LABELS: Record<string, string> = {
  Africa: 'Châu Phi',
  America: 'Châu Mỹ',
  Antarctica: 'Nam Cực',
  Arctic: 'Bắc Cực',
  Asia: 'Châu Á',
  Atlantic: 'Đại Tây Dương',
  Australia: 'Châu Úc',
  Brazil: 'Brazil',
  Canada: 'Canada',
  Chile: 'Chile',
  Europe: 'Châu Âu',
  Indian: 'Ấn Độ Dương',
  Mexico: 'Mexico',
  Pacific: 'Thái Bình Dương',
  US: 'Hoa Kỳ',
  Etc: 'Chuẩn cố định',
};

const CURATED_MAP = new Map(CURATED_TIMEZONE_ENTRIES.map((item) => [item.timezone, item]));
export const FRIENDLY_TIMEZONE_OPTIONS: readonly FriendlyTimezoneOption[] = CURATED_TIMEZONE_ENTRIES.map((item) => ({
  timezone: item.timezone,
  label: item.label,
  searchText: normalizeSearchText(`${item.label} ${item.keywords}`),
}));

export function normalizeSearchText(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/gi, 'd')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

export function isSupportedTimezone(timezone?: string | null): boolean {
  const trimmed = timezone?.trim();
  if (!trimmed || trimmed.length > 64) {
    return false;
  }
  return Boolean(moment.tz.zone(trimmed));
}

const BACKEND_ZONE_ID_SET: ReadonlySet<string> = new Set(BACKEND_ZONE_IDS);

/**
 * True only for identifiers the Backend accepts (exact `ZoneId.getAvailableZoneIds()` membership,
 * length <= 64) and the client can render. Use it to decide what may be OFFERED as a new choice.
 * Display and existing-record helpers keep using `isSupportedTimezone` so saved history is never
 * rewritten or canonicalized to fit the picker.
 */
export function isBackendSelectableTimezone(timezone?: string | null): boolean {
  const trimmed = timezone?.trim();
  return Boolean(trimmed && trimmed === timezone && BACKEND_ZONE_ID_SET.has(trimmed) && isSupportedTimezone(trimmed));
}

function buildFriendlyLabel(trimmed: string): string {
  const curated = CURATED_MAP.get(trimmed);
  if (curated) {
    return curated.label;
  }
  const legacy = LEGACY_ALIAS_LABELS[trimmed];
  if (legacy) {
    return legacy.label;
  }
  if (trimmed.startsWith('Etc/GMT')) {
    const fixedOffset = moment.tz(0, trimmed).format('Z');
    const posixSuffix = trimmed.slice('Etc/GMT'.length);
    return `Giờ cố định lệch chuẩn (UTC${fixedOffset} · nhóm ${posixSuffix || '0'})`;
  }
  const segments = trimmed.split('/');
  if (segments.length >= 2) {
    const region = REGION_LABELS[segments[0]] ?? segments[0].replace(/_/g, ' ');
    const city = segments[segments.length - 1].replace(/_/g, ' ');
    if (segments.length >= 3) {
      const subRegion = segments.slice(1, -1).map((part) => part.replace(/_/g, ' ')).join(', ');
      return `Khung giờ ${city} (${subRegion}, ${region})`;
    }
    return `Khung giờ ${city} (${region})`;
  }
  return `Khung giờ ${trimmed.replace(/_/g, ' ')}`;
}

function buildSearchText(trimmed: string, label: string): string {
  const curated = CURATED_MAP.get(trimmed);
  const legacy = LEGACY_ALIAS_LABELS[trimmed];
  const spacedTokens = trimmed.replace(/[/_-]+/g, ' ');
  return normalizeSearchText(`${label} ${curated?.keywords ?? ''} ${legacy?.keywords ?? ''} ${spacedTokens}`);
}

let cachedCatalog: FriendlyTimezoneOption[] | null = null;

/**
 * Returns every selectable timezone: the intersection of the Backend's verified Java ZoneId catalog
 * (see backendTimezoneCatalog.ts and scripts/generate-backend-timezone-catalog.cjs) and the zones the
 * client can render with moment-timezone, preserving exact aliases such as Asia/Saigon and
 * Pacific/Auckland, with friendly labels. Extras (current/saved values) are added only when they are
 * Backend-selectable; a rejected saved ID stays visible through `formatTimezoneDisplay` but is never offered.
 */
export function getSupportedTimezoneOptions(
  extraTimezones: readonly (string | null | undefined)[] = [],
): FriendlyTimezoneOption[] {
  if (!cachedCatalog) {
    const priorityOrder = new Map(CURATED_TIMEZONE_ENTRIES.map((item, idx) => [item.timezone, idx]));
    const names = moment.tz.names().filter((name) => isBackendSelectableTimezone(name));
    const entries: FriendlyTimezoneOption[] = names.map((timezone) => {
      const label = buildFriendlyLabel(timezone);
      return {
        timezone,
        label,
        searchText: buildSearchText(timezone, label),
      };
    });
    entries.sort((a, b) => {
      const pa = priorityOrder.get(a.timezone);
      const pb = priorityOrder.get(b.timezone);
      if (pa !== undefined && pb !== undefined) return pa - pb;
      if (pa !== undefined) return -1;
      if (pb !== undefined) return 1;
      const aHasSlash = a.timezone.includes('/');
      const bHasSlash = b.timezone.includes('/');
      if (aHasSlash !== bHasSlash) return aHasSlash ? -1 : 1;
      return a.label.localeCompare(b.label, 'vi');
    });
    cachedCatalog = entries;
  }

  if (!extraTimezones.length) {
    return cachedCatalog;
  }
  const seen = new Set(cachedCatalog.map((item) => item.timezone));
  const extras: FriendlyTimezoneOption[] = [];
  for (const candidate of extraTimezones) {
    const trimmed = candidate?.trim();
    if (!trimmed || seen.has(trimmed) || !isBackendSelectableTimezone(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    const label = buildFriendlyLabel(trimmed);
    extras.push({
      timezone: trimmed,
      label,
      searchText: buildSearchText(trimmed, label),
    });
  }
  return extras.length ? [...extras, ...cachedCatalog] : cachedCatalog;
}

/**
 * Returns the default timezone strictly for truly brand-new record intents.
 * Never reads account or device timezone so new creations follow approved Vietnam defaults.
 */
export function resolveNewRecordDefaultTimezone(): string {
  return DEFAULT_APP_TIMEZONE;
}

/**
 * Preserves an existing record's explicit/saved timezone without rewriting non-VN history.
 * Falls back only when the existing timezone is missing or invalid.
 */
export function resolveExistingRecordTimezone(
  existingTimezone?: string | null,
  fallbackTimezone = DEFAULT_APP_TIMEZONE,
): string {
  const trimmed = existingTimezone?.trim();
  if (trimmed && isSupportedTimezone(trimmed)) {
    return trimmed;
  }
  const fallbackTrimmed = fallbackTimezone?.trim();
  if (fallbackTrimmed && isSupportedTimezone(fallbackTrimmed)) {
    return fallbackTrimmed;
  }
  return DEFAULT_APP_TIMEZONE;
}

function parseInstantTimestamp(at?: string | number | Date | null): number | null {
  if (at === undefined || at === null || at === '') {
    return null;
  }
  if (typeof at === 'number') {
    return Number.isFinite(at) ? at : null;
  }
  if (at instanceof Date) {
    const ms = at.getTime();
    return Number.isFinite(ms) ? ms : null;
  }
  const str = String(at).trim();
  // Require a timestamp with time-of-day (not date-only) so offset is anchored to an exact instant.
  if (!/T\d{2}:\d{2}/.test(str)) {
    return null;
  }
  const parsed = Date.parse(str);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Formats any valid IANA timezone identifier as a human-readable localized label
 * without exposing technical IANA codes in normal UI. Missing/legacy empty values
 * remain explicitly distinguished and are never labeled as Vietnam time.
 *
 * When `at` is omitted, never appends a wall-clock `now()` offset so historical
 * or cross-season labels never contradict their record season. When an exact `at`
 * instant is provided, computes and appends the UTC offset as of `at`.
 */
export function formatTimezoneDisplay(
  timezone?: string | null,
  emptyLabel = 'Chưa có múi giờ',
  at?: string | number | Date | null,
): string {
  const trimmed = timezone?.trim();
  if (!trimmed || !isSupportedTimezone(trimmed)) {
    return emptyLabel;
  }
  const label = buildFriendlyLabel(trimmed);
  const instantMs = parseInstantTimestamp(at);
  if (instantMs !== null && !trimmed.startsWith('Etc/GMT')) {
    const offset = moment.tz(instantMs, trimmed).format('Z');
    return `${label} (UTC${offset})`;
  }
  return label;
}

export interface StrategyVersionTimezoneLike {
  versionNumber?: number;
  effectiveFrom: string;
  effectiveUntil: string | null;
  dateSemantics: string;
  timezone: string | null;
}

export interface StrategyGoalTimezoneLike {
  timezone: string | null;
  versions?: readonly StrategyVersionTimezoneLike[];
}

function versionOrderComparator(a: StrategyVersionTimezoneLike, b: StrategyVersionTimezoneLike): number {
  const numDiff = (b.versionNumber ?? 0) - (a.versionNumber ?? 0);
  if (numDiff !== 0) return numDiff;
  return b.effectiveFrom.localeCompare(a.effectiveFrom);
}

/**
 * Strictly resolves the saved calendar timezone for a specific target date using
 * HALF_OPEN interval semantics `[effectiveFrom, effectiveUntil)`.
 * Returns `null` when `targetDay` is absent, invalid, or uncovered by every version;
 * NEVER falls back to the latest/ended version.
 */
export function resolveStrategyGoalTimezone(
  goal: StrategyGoalTimezoneLike | null | undefined,
  targetDay?: string | null,
): string | null {
  if (!goal || !targetDay || !/^\d{4}-\d{2}-\d{2}$/.test(targetDay)) {
    return null;
  }
  const covering = (goal.versions ?? [])
    .filter(
      (version): version is StrategyVersionTimezoneLike & { timezone: string } =>
        version.dateSemantics === 'HALF_OPEN' &&
        isSupportedTimezone(version.timezone) &&
        version.effectiveFrom <= targetDay &&
        (version.effectiveUntil === null || version.effectiveUntil > targetDay),
    )
    .sort(versionOrderComparator);
  return covering[0]?.timezone ?? null;
}

/**
 * Structural helper strictly for strategy-level mutations (such as `CHANGE_TARGET`)
 * that inherit timezone from the latest half-open strategy version or goal envelope,
 * rather than resolving a specific calendar date.
 */
export function resolveLatestStrategyVersionTimezone(
  goal: StrategyGoalTimezoneLike | null | undefined,
): string | null {
  if (!goal) {
    return null;
  }
  const halfOpen = (goal.versions ?? [])
    .filter(
      (version): version is StrategyVersionTimezoneLike & { timezone: string } =>
        version.dateSemantics === 'HALF_OPEN' && isSupportedTimezone(version.timezone),
    )
    .sort(versionOrderComparator);
  if (halfOpen[0]?.timezone) {
    return halfOpen[0].timezone;
  }
  if (isSupportedTimezone(goal.timezone)) {
    return goal.timezone;
  }
  return null;
}
