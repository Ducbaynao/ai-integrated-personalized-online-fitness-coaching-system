import moment from 'moment-timezone';

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

export function proposalTimezones(search: string): string[] {
  const term = search.trim().toLowerCase();
  return moment.tz.names().filter((name) => name.toLowerCase().includes(term)).slice(0, 60);
}

export function validProposalOffsets(zoneName: string, local: string): { offset: string; instant: number }[] {
  const zone = moment.tz.zone(zoneName);
  const parts = LOCAL.exec(local);
  if (!zone || !parts) return [];
  const [year, month, day, hour, minute] = parts.slice(1).map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  if (new Date(wall).toISOString().slice(0, 16) !== local) return [];
  const offsets = [...new Set(zone.offsets)];
  return offsets.flatMap((minutesWest) => {
    const instant = wall + minutesWest * 60_000;
    if (zone.utcOffset(instant) !== minutesWest || moment.tz(instant, zoneName).format('YYYY-MM-DDTHH:mm') !== local) return [];
    const east = -minutesWest;
    const sign = east < 0 ? '-' : '+';
    const absolute = Math.abs(east);
    return [{ offset: `${sign}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`, instant }];
  }).sort((a, b) => a.instant - b.instant);
}

export function isMonday(value: string): boolean {
  const date = moment(value, 'YYYY-MM-DD', true);
  return date.isValid() && date.isoWeekday() === 1;
}

export function isInSessionWeek(anchor: string, week: number, local: string): boolean {
  const date = moment(local.slice(0, 10), 'YYYY-MM-DD', true);
  const monday = moment(anchor, 'YYYY-MM-DD', true).add(week - 1, 'weeks');
  return date.isValid() && date.isSameOrAfter(monday, 'day') && date.isBefore(monday.clone().add(7, 'days'), 'day');
}
