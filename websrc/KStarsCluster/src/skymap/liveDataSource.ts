import { fetchObservatoryInfo, fetchArtificialHorizon, TERRAIN_IMAGE_URL } from './horizonApi';
import { fetchScheduleFileJobs } from '../api/actions';
import type { SkyMapDataSource } from './dataSource';
import type { AstrobinFootprint, AstrobinImageDetail, SurveyOption } from './types';

/** Custom entries verified against each survey's own HiPS `properties` file (frame/order/tile format).
 * None of the NSNS palette entries are real simg.de surveys — simg.de only publishes single-channel
 * Hα/[OIII]/[SII] HiPS (starless) and its own fixed-mapping combos (ohs8/hbr8/rgb8, not starless).
 * Our own backend (HipsProxyServlet, /hips/*) builds the palettes two different ways depending on
 * which of those two source kinds it starts from — see the servlet's javadoc:
 *  - "sho"/"hso" re-permute ohs8's own channels, so they keep ohs8's stars.
 *  - "sho-sl"/"hso-sl"/"ohs-sl" recombine the starless single-channel surveys from scratch, so
 *    they're starless too (the "-sl" suffix).
 * Every other NSNS entry also goes through our backend (/hips/{survey}/*, straight passthrough)
 * rather than simg.de directly — same HipsProxyServlet, just caching each tile instead of
 * recombining it, so repeat pans/zooms don't keep re-hitting simg.de's server.
 * SHO is listed first — SkyMapCard.tsx picks whichever entry is first as its default survey. */
export const liveSurveys: SurveyOption[] = [
  { id: 'nsns-sho', label: 'NSNS SHO (Hubble palette)', custom: { url: '/hips/sho', frame: 'equatorial', order: 6 } },
  { id: 'nsns-hso', label: 'NSNS HSO (Hα/[SII]/[OIII])', custom: { url: '/hips/hso', frame: 'equatorial', order: 6 } },
  { id: 'nsns-sho-sl', label: 'NSNS SHO (starless)', custom: { url: '/hips/sho-sl', frame: 'equatorial', order: 6 } },
  { id: 'nsns-hso-sl', label: 'NSNS HSO (starless)', custom: { url: '/hips/hso-sl', frame: 'equatorial', order: 6 } },
  { id: 'nsns-ohs-sl', label: 'NSNS OHS (starless)', custom: { url: '/hips/ohs-sl', frame: 'equatorial', order: 6 } },
  { id: 'nsns-ohs8', label: 'NSNS [OIII]+Hα+[SII]', custom: { url: '/hips/ohs8', frame: 'equatorial', order: 6 } },
  { id: 'dss2-color', label: 'DSS2 (color)', builtin: 'P/DSS2/color' },
  { id: 'nsns-rgb8', label: 'NSNS RGB continuum', custom: { url: '/hips/rgb8', frame: 'equatorial', order: 5 } },
  { id: 'nsns-hbr8', label: 'NSNS Hα + continuum (color)', custom: { url: '/hips/hbr8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-halpha8', label: 'NSNS Hα (8-bit)', custom: { url: '/hips/halpha8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-oiii8', label: 'NSNS [OIII] (8-bit)', custom: { url: '/hips/oiii8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-sii8', label: 'NSNS [SII] (8-bit)', custom: { url: '/hips/sii8', frame: 'equatorial', order: 6 } },
];

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
  getSurveys: () => liveSurveys,
};
