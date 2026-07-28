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

export async function fetchAllskyChart(cam: string, limitS: number): Promise<AllskyPoint[]> {
  const res = await fetch(`/cmd/allsky/chart?${new URLSearchParams({ cam, limitS: String(limitS) }).toString()}`);
  return res.json();
}
