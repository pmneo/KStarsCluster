import type { ObservatoryInfo, ArtificialHorizonRegion } from './types';

export type { ObservatoryInfo, ArtificialHorizonRegion };

/** -999/-999 is KStarsConfig's own "never configured" sentinel (see KStarsConfig.getLatitude). */
export function isValidLocation(info: ObservatoryInfo): boolean {
  return info.latitude !== -999 && info.longitude !== -999;
}

export async function fetchObservatoryInfo(): Promise<ObservatoryInfo> {
  const res = await fetch('/observatory/info');
  if (!res.ok) throw new Error(`observatory/info failed: ${res.status}`);
  return res.json();
}

export async function fetchArtificialHorizon(): Promise<ArtificialHorizonRegion[]> {
  const res = await fetch('/observatory/artificial-horizon');
  if (!res.ok) throw new Error(`observatory/artificial-horizon failed: ${res.status}`);
  return res.json();
}

/** The user's own "Terrain" panorama (KStars: View > Show Terrain) — only meaningful once
 * ObservatoryInfo.hasTerrain is true. Cached by the browser (see ObservatoryServlet's ETag/
 * Cache-Control), so fetching this is cheap on every mount after the first. */
export const TERRAIN_IMAGE_URL = '/observatory/terrain.png';
