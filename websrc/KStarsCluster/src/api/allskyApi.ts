export interface AllskyLatest {
  url?: string;
  stars?: number;
  moonmode?: boolean;
  ts?: number;
  starsAvg?: number;
}

export interface AllskyPoint {
  ts: number;
  stars: number;
  /** Relative to the indi-allsky web root, same as AllskyLatest.url — pass to allskyImageUrl(). */
  url?: string;
}

export interface AllskyCameraInfo {
  label: string;
  /** false for a camera pointed at the dome interior rather than the sky — no point showing a
   * star count/history for that one. */
  showDetails: boolean;
}

export function allskyImageUrl(cam: string, path: string): string {
  return `/cmd/allsky/image?${new URLSearchParams({ cam, path }).toString()}`;
}

export async function fetchAllskyCameras(): Promise<Record<string, AllskyCameraInfo>> {
  const res = await fetch('/cmd/allsky/cameras');
  return res.json();
}

export async function fetchAllskyLatest(cam: string): Promise<AllskyLatest> {
  const res = await fetch(`/cmd/allsky/latest?${new URLSearchParams({ cam }).toString()}`);
  return res.json();
}

/** `timestampMs`, when given, anchors the window there instead of at "now" — the backend's
 * `timestamp` param is epoch SECONDS (indi-allsky's own convention, confirmed against a real
 * instance), converted here so every other caller in this codebase can keep working in
 * milliseconds like everywhere else. The returned window is [timestamp-limitS, timestamp], i.e.
 * ending AT timestamp, not centered on it — pass `timestampMs + (limitS/2)*1000` for a window
 * centered on a specific moment (see App.tsx's on-demand allsky-match lookup). */
export async function fetchAllskyChart(cam: string, limitS: number, timestampMs?: number): Promise<AllskyPoint[]> {
  const params: Record<string, string> = { cam, limitS: String(limitS) };
  if (timestampMs !== undefined) params.timestamp = String(Math.round(timestampMs / 1000));
  const res = await fetch(`/cmd/allsky/chart?${new URLSearchParams(params).toString()}`);
  return res.json();
}

export interface AllskyMatch {
  cam: string;
  label: string;
  point: AllskyPoint;
  /** point.ts - ts — negative means this allsky shot was taken before the capture, positive after. */
  deltaMs: number;
}

/** One nearest-in-time allsky point per configured camera, for comparing against a given capture
 * timestamp (the Session Timeline's click/hover-to-compare feature — see App.tsx and
 * CaptureCompareStrip). Deliberately one match per camera rather than the two closest points
 * overall: each camera's own closest shot is the meaningful comparison regardless of how the other
 * camera's history happens to be spaced. `history` needs to actually cover `ts` — App.tsx fetches
 * it on demand, centered on the selected capture, rather than keeping a standing wide-window poll
 * running at all times. */
export function nearestAllskyMatches(
  ts: number,
  history: Record<string, AllskyPoint[]>,
  cameras: Record<string, AllskyCameraInfo>,
): AllskyMatch[] {
  const matches: AllskyMatch[] = [];
  for (const [cam, info] of Object.entries(cameras)) {
    const points = history[cam];
    if (!points || points.length === 0) continue;
    let best = points[0];
    let bestAbsDelta = Math.abs(points[0].ts - ts);
    for (const p of points) {
      const absDelta = Math.abs(p.ts - ts);
      if (absDelta < bestAbsDelta) { best = p; bestAbsDelta = absDelta; }
    }
    matches.push({ cam, label: info.label, point: best, deltaMs: best.ts - ts });
  }
  return matches;
}
