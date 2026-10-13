import Ajv2020 from 'ajv/dist/2020.js';

// F01 contract scope limitation: strictly accepts seconds 00-59.
// Timestamps with second=60 (leap seconds) are explicitly blocked in this iteration
// rather than claiming partial RFC 3339 leap second verification.
export const RFC3339_DATE_TIME_REGEX =
  /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])[Tt]([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.\d+)?(?:[Zz]|([+-])([01]\d|2[0-3]):([0-5]\d))$/;

export const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const DAYS_IN_MONTH = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

export function isLeapYear(year) {
  return (year % 4 === 0 && year % 100 !== 0) || (year % 400 === 0);
}

export function isValidRfc3339DateTime(str) {
  if (typeof str !== 'string') return false;
  const match = RFC3339_DATE_TIME_REGEX.exec(str);
  if (!match) return false;

  const year = parseInt(match[1], 10);
  const month = parseInt(match[2], 10);
  const day = parseInt(match[3], 10);

  let maxDay = DAYS_IN_MONTH[month];
  if (month === 2 && isLeapYear(year)) {
    maxDay = 29;
  }

  return day <= maxDay;
}

export function registerCommonFormats(ajv) {
  ajv.addFormat('uuid', UUID_REGEX);
  ajv.addFormat('date-time', {
    validate: (val) => isValidRfc3339DateTime(val)
  });
}

export function createAjv(options = {}) {
  const ajv = new Ajv2020({ strict: false, allErrors: true, ...options });
  registerCommonFormats(ajv);
  return ajv;
}
