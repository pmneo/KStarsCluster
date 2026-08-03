import { fetchObservatoryInfo, fetchArtificialHorizon, TERRAIN_IMAGE_URL } from './horizonApi';
import { fetchScheduleFileJobs } from '../api/actions';
import type { SkyMapDataSource } from './dataSource';
import type { AstrobinFootprint, AstrobinImageDetail } from './types';

/** Server-proxied (see AstrobinProxyServlet — the underlying AstroBin endpoints send no
 * Access-Control-Allow-Origin, so the browser can't call them directly) and cached for an hour
 * there, so this itself is cheap and doesn't need its own client-side caching beyond "already
 * fetched once this page session" (see SkyMapCard's own astrobinFetchedRef). */
async function fetchAstrobinFootprints(): Promise<AstrobinFootprint[]> {
  const res = await fetch('/astrobin/footprints');
  if (!res.ok) throw new Error(`astrobin footprints request failed: ${res.status}`);
  return res.json();
}

/** Unlike the bulk footprint listing, capture date isn't worth fetching for every image up
 * front — it's only ever shown for the one footprint someone actually clicks open. */
async function fetchAstrobinImageDetail(hash: string): Promise<AstrobinImageDetail> {
  const res = await fetch(`/astrobin/image-detail?hash=${encodeURIComponent(hash)}`);
  if (!res.ok) throw new Error(`astrobin image detail request failed: ${res.status}`);
  return res.json();
}

/** SkyMapCard's data source when embedded in this dashboard — every method backed by this
 * server's own live endpoints (Ekos D-Bus reads, AstroBin proxy, observatory config on disk).
 * A future public-site deployment would supply a different SkyMapDataSource instead (e.g. reading
 * one static JSON config dump), passed to the same component unchanged. */
export const liveSkyMapDataSource: SkyMapDataSource = {
  getObservatoryInfo: fetchObservatoryInfo,
  getArtificialHorizon: fetchArtificialHorizon,
  getTerrainImageUrl: () => TERRAIN_IMAGE_URL,
  getAstrobinFootprints: fetchAstrobinFootprints,
  getAstrobinImageDetail: fetchAstrobinImageDetail,
  getScheduleFileJobs: fetchScheduleFileJobs,
};
