export interface AllskyLatest {
  url?: string;
  stars?: number;
  jsqm?: number;
  moonmode?: boolean;
  ts?: number;
  starsAvg?: number;
}

export interface AllskyPoint {
  ts: number;
  stars: number;
  jsqm: number;
}

export function allskyImageUrl(path: string): string {
  return `/cmd/allsky/image?${new URLSearchParams({ path }).toString()}`;
}

export async function fetchAllskyLatest(): Promise<AllskyLatest> {
  const res = await fetch('/cmd/allsky/latest');
  return res.json();
}

export async function fetchAllskyChart(limitS: number): Promise<AllskyPoint[]> {
  const res = await fetch(`/cmd/allsky/chart?${new URLSearchParams({ limitS: String(limitS) }).toString()}`);
  return res.json();
}
