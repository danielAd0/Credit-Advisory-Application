import { ApiError, STALE_VERSION } from './http';

/**
 * The server's own explanation of a failure (the ProblemDetail `detail`, plus one line per bad
 * field on a 400), or null when it gave none. It never contains a full ID number.
 */
export function detailOf(err: unknown): string | null {
  if (!(err instanceof ApiError)) return null;
  if (err.errors.length > 0) {
    const fields = err.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    return `${err.detail ?? 'Some details are not valid'} (${fields})`;
  }
  return err.detail;
}

/** True when someone else saved the same record first (409, stale version). */
export const isStale = (err: unknown) => err instanceof ApiError && err.type === STALE_VERSION;
