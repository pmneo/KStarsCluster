import { useEffect, useRef, useState } from 'react';
import type { SchedulerJob } from '../api/types';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';

// Aladin Lite v3 is loaded via <script> in index.html, not bundled — it ships no official types.
declare global {
  interface Window {
    A: any;
  }
}

function ExpandIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 9V4h5" />
      <path d="M15 4h5v5" />
      <path d="M20 15v5h-5" />
      <path d="M9 20H4v-5" />
    </svg>
  );
}

function CompressIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 4v5H4" />
      <path d="M15 4v5h5" />
      <path d="M20 15h-5v5" />
      <path d="M4 15h5v5" />
    </svg>
  );
}

function GearIcon() {
  const teeth = [0, 45, 90, 135, 180, 225, 270, 315];
  return (
    <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
      {teeth.map((deg) => (
        <rect key={deg} x="10.5" y="0.5" width="3" height="5" rx="1" transform={`rotate(${deg} 12 12)`} />
      ))}
      <circle cx="12" cy="12" r="7" fill="none" stroke="currentColor" strokeWidth="2" />
      <circle cx="12" cy="12" r="2.5" />
    </svg>
  );
}

function SlidersIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <line x1="4" y1="6" x2="20" y2="6" />
      <circle cx="9" cy="6" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <circle cx="15" cy="12" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="18" x2="20" y2="18" />
      <circle cx="7" cy="18" r="2" fill="currentColor" stroke="none" />
    </svg>
  );
}

interface SurveyOption {
  id: string;
  label: string;
  builtin?: string;
  custom?: { url: string; frame: string; order: number };
}

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
 * SHO is listed first — it's the default survey (see surveyId's initial state below). */
const SURVEYS: SurveyOption[] = [
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

interface Props {
  mountCoords?: { ra: number; dec: number };
  activeJob: SchedulerJob | null;
  fov?: { widthArcmin: number; heightArcmin: number };
  pa?: number;
  lastImageFilename?: string;
}

/** Four corners of a centerRa/centerDec-centered rectangle, widthDeg x heightDeg, rotated by paDeg
 * (East of North). Corners are pre-divided by cos(dec) on the RA axis — Aladin's own projection
 * re-applies that scaling when rendering RA/DEC, so this cancels out to the correct on-sky size.
 * dx is flipped (+dx = West, not East) — verified empirically: RA increases to the left on an
 * unmirrored equatorial display, so a plain +East-is-right offset renders the overlay image
 * mirrored left-right. */
function fovCorners(centerRa: number, centerDec: number, widthDeg: number, heightDeg: number, paDeg: number): [number, number][] {
  const paRad = (paDeg * Math.PI) / 180;
  const cosDec = Math.max(0.01, Math.cos((centerDec * Math.PI) / 180));
  const halfW = widthDeg / 2;
  const halfH = heightDeg / 2;
  const offsets: [number, number][] = [[halfW, -halfH], [-halfW, -halfH], [-halfW, halfH], [halfW, halfH]];
  return offsets.map(([dx, dy]) => {
    const rx = dx * Math.cos(paRad) - dy * Math.sin(paRad);
    const ry = dx * Math.sin(paRad) + dy * Math.cos(paRad);
    return [centerRa + rx / cosDec, centerDec + ry];
  });
}

/** Positions a plain screen-space <img> over a sky-registered rectangle (world corners in
 * [top-left, top-right, bottom-right, bottom-left] winding order) by projecting them to pixels —
 * the technique the live "last image" overlay already uses, generalized so any number of images
 * (e.g. one per AstroBin footprint) can share it. Aladin's own image layers need real HiPS/WCS
 * tiling, which a one-off JPEG thumbnail doesn't have, so this fakes it with CSS instead.
 *
 * `extraHalfTurn` exists because the two callers need opposite answers to the same question, and
 * there's no way to derive it from the corners alone: the live-capture caller's corners come from
 * fovCorners() using Ekos's own `pa`, which (empirically) needs a +180° correction to stop the
 * actual photo rendering upside down; AstroBin's corners are real solved RA/Dec per corner
 * (verified against a named object's true catalog position landing exactly where its pixel
 * position predicts), and adding that same +180° there just rotates a correct answer into a wrong
 * one — confirmed the hard way when it flipped every AstroBin footprint 180°, not just the
 * one-off mirrored-solve cases the corners were adopted to fix in the first place. */
function positionFootprintImage(img: HTMLElement, aladin: any, corners: [number, number][], extraHalfTurn: boolean) {
  const px = corners.map(([ra, dec]) => aladin.world2pix(ra, dec));
  // world2pix returns null/undefined for points its current projection can't map (e.g. an
  // AstroBin footprint on the opposite side of the sky from wherever the view happens to be) —
  // rather than crashing the whole redraw() (which would also skip the live FOV overlay below
  // it), just leave this one image hidden until it's somewhere projectable.
  if (px.some((p) => !p)) {
    img.style.display = 'none';
    return;
  }
  const cx = (px[0][0] + px[2][0]) / 2;
  const cy = (px[0][1] + px[2][1]) / 2;
  const topW = Math.hypot(px[1][0] - px[0][0], px[1][1] - px[0][1]);
  const rightH = Math.hypot(px[2][0] - px[1][0], px[2][1] - px[1][1]);
  const angle = (Math.atan2(px[1][1] - px[0][1], px[1][0] - px[0][0]) * 180) / Math.PI + (extraHalfTurn ? 180 : 0);

  img.style.display = 'block';
  img.style.width = `${topW}px`;
  img.style.height = `${rightH}px`;
  img.style.left = `${cx}px`;
  img.style.top = `${cy}px`;
  img.style.marginLeft = `${-topW / 2}px`;
  img.style.marginTop = `${-rightH / 2}px`;
  img.style.transform = `rotate(${angle}deg)`;
}

const FOLLOW_MOUNT_KEY = 'skymap.followMount';
const SHOW_LAST_IMAGE_KEY = 'skymap.showLastImage';
const SHOW_NGC_KEY = 'skymap.showNgc';
const SHOW_SH2_KEY = 'skymap.showSh2';
const SHOW_ASTROBIN_KEY = 'skymap.showAstrobin';
// Real width is CSS-defined (see .sky-map-astrobin-popover); the height is only an estimate since
// the actual rendered height depends on title wrapping and isn't known until after it paints —
// good enough for clamping the popover to stay on-screen without needing a post-paint measurement.
const ASTROBIN_POPOVER_WIDTH = 220;
const ASTROBIN_POPOVER_HEIGHT_ESTIMATE = 140;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
const PLANNING_FOV_ENABLED_KEY = 'skymap.planningFov.enabled';
const PLANNING_FOV_SENSOR_WIDTH_KEY = 'skymap.planningFov.sensorWidthPx';
const PLANNING_FOV_SENSOR_HEIGHT_KEY = 'skymap.planningFov.sensorHeightPx';
const PLANNING_FOV_PIXEL_SIZE_KEY = 'skymap.planningFov.pixelSizeUm';
const PLANNING_FOV_FOCAL_LENGTH_KEY = 'skymap.planningFov.focalLengthMm';
const PLANNING_FOV_ROTATION_KEY = 'skymap.planningFov.rotationDeg';
const VIEW_KEY = 'skymap.view';
const DEFAULT_FOV_DEG = 10;
// ASI2600MM Pro (6248x4176, 3.76µm) — just a starting point for the calculator below, not tied
// to whatever camera is actually connected.
const DEFAULT_SENSOR_WIDTH_PX = 6248;
const DEFAULT_SENSOR_HEIGHT_PX = 4176;
const DEFAULT_PIXEL_SIZE_UM = 3.76;
const DEFAULT_FOCAL_LENGTH_MM = 418;
const ARCMIN_PER_RADIAN = (180 / Math.PI) * 60;

/** Small-angle FOV approximation (accurate well beyond any real camera/focal-length combo):
 * sensor dimension in mm, divided by focal length, is the angle in radians. */
function sensorFovArcmin(pixels: number, pixelSizeUm: number, focalLengthMm: number): number {
  if (focalLengthMm <= 0) return 0;
  const sensorMm = (pixels * pixelSizeUm) / 1000;
  return (sensorMm / focalLengthMm) * ARCMIN_PER_RADIAN;
}

/** Sinnott's NGC 2000.0 (~13000 NGC/IC objects) and Sharpless's Sh2 HII-region catalogue
 * (313 objects) — both small enough to load whole in one VizieR cone search rather than
 * re-querying as the view pans/zooms, so a 180° radius (the whole sky, from any center) is
 * fetched once per toggle-on and then just shown/hidden from then on. */
const NGC_VIZIER_CAT = 'VII/118/ngc2000';
const SH2_VIZIER_CAT = 'VII/20/catalog';
const OVERLAY_CATALOG_RADIUS_DEG = 180;

// Both catalogs' size columns are confirmed (via each ReadMe) to be a single largest-dimension
// value in arcmin, not a proper major/minor ellipse — a circle of that diameter is the closest
// honest approximation available, and it's already a lot more real than a fixed-size marker.
// Objects with no recorded size (common for faint/small NGC entries) still get a small circle
// rather than nothing, since a boundary that vanishes for "unknown size" reads as a bug.
const MIN_BOUNDARY_RADIUS_DEG = 0.015;

function sizeArcminToRadiusDeg(sizeArcmin: string | undefined): number {
  const value = parseFloat(sizeArcmin ?? '');
  if (!Number.isFinite(value) || value <= 0) return MIN_BOUNDARY_RADIUS_DEG;
  return Math.max(MIN_BOUNDARY_RADIUS_DEG, value / 2 / 60);
}

/** Builds a circle-per-source graphic overlay sized by each source's real angular diameter,
 * alongside (not instead of) the small click-for-details catalog marker `cat` already carries —
 * the marker gives a precise, clickable center point; this overlay is the actual boundary. */
function buildBoundaryOverlay(aladin: any, cat: any, sizeField: string, color: string): any {
  const overlay = window.A.graphicOverlay({ color, lineWidth: 1 });
  aladin.addOverlay(overlay);
  cat.getSources().forEach((source: any) => {
    const radiusDeg = sizeArcminToRadiusDeg(source.data?.[sizeField]);
    overlay.add(window.A.circle(source.ra, source.dec, radiusDeg));
  });
  return overlay;
}

/** Inverse of fovCorners' rotation: given a point's world RA/DEC, is it inside the
 * centerRa/centerDec-centered, widthDeg x heightDeg rectangle rotated by paDeg? Used to turn a
 * SIMBAD cone search (necessarily circular) into an accurate "inside this rectangle" test. */
function isInsideFov(
  centerRa: number, centerDec: number, objRa: number, objDec: number,
  widthDeg: number, heightDeg: number, paDeg: number,
): boolean {
  const paRad = (paDeg * Math.PI) / 180;
  const cosDec = Math.max(0.01, Math.cos((centerDec * Math.PI) / 180));
  const rx = (objRa - centerRa) * cosDec;
  const ry = objDec - centerDec;
  const dx = rx * Math.cos(paRad) + ry * Math.sin(paRad);
  const dy = -rx * Math.sin(paRad) + ry * Math.cos(paRad);
  return Math.abs(dx) <= widthDeg / 2 && Math.abs(dy) <= heightDeg / 2;
}

/** SIMBAD's own object-type taxonomy (https://simbad.cds.unistra.fr/guide/otypes.htx) files
 * planetary nebulae and Herbig-Haro objects under "stars" and puts star clusters in their own
 * "sets of stars" branch — neither reads as "a star" or "a galaxy" to an imager, so this is a
 * hand-picked allowlist of `otype` label values (not a blanket category exclusion) covering
 * every nebula/cloud/remnant/cluster type plus their named sub-regions, and nothing else.
 * Maps each to a short human-readable label for display, since the raw otype strings are terse
 * SIMBAD internal labels (e.g. "SNRemnant", "PoC"). */
const INTERESTING_OTYPES: Record<string, string> = {
  HIIReg: 'HII region',
  PlanetaryNeb: 'Planetary nebula',
  GalNeb: 'Nebula',
  DarkNeb: 'Dark nebula',
  RefNeb: 'Reflection nebula',
  SNRemnant: 'Supernova remnant',
  MolCld: 'Molecular cloud',
  Cloud: 'Cloud',
  StarFormingReg: 'Star forming region',
  ISM: 'Interstellar medium',
  ComGlob: 'Cometary globule',
  HVCld: 'High-velocity cloud',
  Bubble: 'Bubble',
  denseCore: 'Dense core',
  Filament: 'Filament',
  Globule: 'Globule',
  HIshell: 'Shell',
  HerbigHaroObj: 'Herbig-Haro object',
  'Cluster*': 'Star cluster',
  OpenCluster: 'Open cluster',
  GlobCluster: 'Globular cluster',
  Association: 'Association of stars',
  PartofCloud: 'Part of cloud/nebula',
  Region: 'Region',
};

/** SIMBAD conesearch results ("distance" is degrees from the search center) filtered to
 * INTERESTING_OTYPES and to the true rotated rectangle (the conesearch itself is a circle sized
 * to comfortably cover the rectangle's corners, so it over-fetches at the edges), then sorted by
 * distance from the FOV center so the most relevant hits are first. */
function findFovObjects(
  sources: any[], centerRa: number, centerDec: number, widthDeg: number, heightDeg: number, paDeg: number,
): SimbadFovObject[] {
  return sources
    .map((s) => s.data)
    .filter((d) => d.otype in INTERESTING_OTYPES)
    .filter((d) => isInsideFov(centerRa, centerDec, parseFloat(d.ra), parseFloat(d.dec), widthDeg, heightDeg, paDeg))
    .map((d) => ({
      // SIMBAD prefixes proper/common names with "NAME " to mark the identifier type — real for
      // its own catalog but just noise for display or for searching other sites by name.
      name: String(d.main_id).replace(/\s+/g, ' ').trim().replace(/^NAME\s+/, ''),
      typeLabel: INTERESTING_OTYPES[d.otype],
      ra: parseFloat(d.ra),
      dec: parseFloat(d.dec),
      sizeArcmin: parseFloat(d.galdim_majaxis),
      distanceArcmin: parseFloat(d.distance) * 60,
    }))
    .sort((a, b) => a.distanceArcmin - b.distanceArcmin);
}

interface SimbadFovObject {
  name: string;
  typeLabel: string;
  ra: number;
  dec: number;
  sizeArcmin: number;
  distanceArcmin: number;
}

function astrobinSearchUrl(name: string): string {
  return `https://www.astrobin.com/search/?q=${encodeURIComponent(name)}`;
}

interface AstrobinFootprintBase {
  title: string;
  hash: string;
  url: string;
  thumbnailUrl: string;
}

/** The backend prefers real corner RA/Dec pairs (AstroBin's own advanced-plate-solve output) when
 * available — that sidesteps rotation-angle sign/handedness guessing entirely, which turned out to
 * be genuinely ambiguous (both the raw "basic" orientation field and a fixed basic-vs-advanced
 * preference were each confirmed wrong on different real images). `corners` is only absent for
 * images that were never advanced-solved, the rarer case — see footprintCorners below. */
type AstrobinFootprint = AstrobinFootprintBase & (
  | { corners: [number, number][]; ra?: undefined }
  | { corners?: undefined; ra: number; dec: number; widthDeg: number; heightDeg: number; orientationDeg: number }
);

function footprintCorners(f: AstrobinFootprint): [number, number][] {
  return f.corners ?? fovCorners(f.ra, f.dec, f.widthDeg, f.heightDeg, f.orientationDeg);
}

/** Server-proxied (see AstrobinProxyServlet — the underlying AstroBin endpoints send no
 * Access-Control-Allow-Origin, so the browser can't call them directly) and cached for an hour
 * there, so this itself is cheap and doesn't need its own client-side caching beyond "already
 * fetched once this page session" (see astrobinFetchedRef below). */
async function fetchAstrobinFootprints(): Promise<AstrobinFootprint[]> {
  const res = await fetch('/astrobin/footprints');
  if (!res.ok) throw new Error(`astrobin footprints request failed: ${res.status}`);
  return res.json();
}

interface AstrobinImageDetail {
  title: string;
  url: string;
  date: string | null;
}

/** Unlike the bulk footprint listing, capture date isn't worth fetching for every image up
 * front — it's only ever shown for the one footprint someone actually clicks open. */
async function fetchAstrobinImageDetail(hash: string): Promise<AstrobinImageDetail> {
  const res = await fetch(`/astrobin/image-detail?hash=${encodeURIComponent(hash)}`);
  if (!res.ok) throw new Error(`astrobin image detail request failed: ${res.status}`);
  return res.json();
}

/** AstroBin's coordinate search (RA/Dec-Koordinaten, an AstroBin Ultimate feature) encodes its
 * filter state in the `p` URL param, reverse engineered (with real Ultimate-account search URLs
 * as ground truth, and confirmed against AstroBin's own bundled JS) as:
 *   1. Build a query string via their own `toQueryString`-equivalent — each filter's value is
 *      `encodeURIComponent(JSON.stringify(...))`. RA is in *minutes of RA* (hours×60, i.e.
 *      degrees×4 — see astroUtilsService.raDegreesToMinutes: `4*l`), everything else plain degrees.
 *   2. That string is itself MessagePack-encoded as a single string value — msgpack's "str 8"
 *      format is a 0xD9 byte, a 1-byte length, then the raw UTF-8 bytes (confirmed byte-for-byte:
 *      captured URLs decompress to exactly length-N text prefixed by 0xD9,N). This step is easy to
 *      miss since it's invisible unless you inflate a real captured URL and notice the leading two
 *      "garbage" bytes are actually N and the string is exactly N bytes long.
 *   3. Deflate that (CompressionStream('deflate') produces the same zlib-wrapped stream pako.deflate
 *      does) and base64 the result.
 * Anyone without Ultimate can still open the resulting link — AstroBin just won't apply the filter
 * for them, same as manually clicking the locked option in their own UI. */
function msgpackEncodeString(str: string): Uint8Array {
  const utf8Bytes = new TextEncoder().encode(str);
  const len = utf8Bytes.length;
  let header: Uint8Array;
  if (len <= 0x1f) header = new Uint8Array([0xa0 | len]);
  else if (len <= 0xff) header = new Uint8Array([0xd9, len]);
  else if (len <= 0xffff) header = new Uint8Array([0xda, (len >> 8) & 0xff, len & 0xff]);
  else header = new Uint8Array([0xdb, (len >>> 24) & 0xff, (len >>> 16) & 0xff, (len >>> 8) & 0xff, len & 0xff]);
  const result = new Uint8Array(header.length + utf8Bytes.length);
  result.set(header, 0);
  result.set(utf8Bytes, header.length);
  return result;
}

async function astrobinCoordsSearchUrl(raDeg: number, decDeg: number, radiusDeg: number): Promise<string> {
  const textFilter = { value: '', matchType: 'ALL', onlySearchInTitlesAndDescriptions: false };
  const coords = { raCenter: raDeg * 4, decCenter: decDeg, radius: radiusDeg };
  const query = `text=${encodeURIComponent(JSON.stringify(textFilter))}`
    + `&coords=${encodeURIComponent(JSON.stringify(coords))}&page=1&pageSize=100`;
  const stream = new Blob([msgpackEncodeString(query) as BlobPart]).stream().pipeThrough(new CompressionStream('deflate'));
  const buffer = await new Response(stream).arrayBuffer();
  let binary = '';
  new Uint8Array(buffer).forEach((b) => { binary += String.fromCharCode(b); });
  return `https://app.astrobin.com/search?p=${encodeURIComponent(btoa(binary))}`;
}

function readStoredBoolean(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  }
  catch {
    return false;
  }
}

function writeStoredBoolean(key: string, value: boolean) {
  try {
    localStorage.setItem(key, String(value));
  }
  catch {
    // storage unavailable (private browsing, quota, ...) — just don't persist
  }
}

function readStoredNumber(key: string, fallback: number): number {
  try {
    const raw = localStorage.getItem(key);
    const value = raw == null ? NaN : Number(raw);
    return Number.isFinite(value) ? value : fallback;
  }
  catch {
    return fallback;
  }
}

function writeStoredNumber(key: string, value: number) {
  try {
    localStorage.setItem(key, String(value));
  }
  catch {
    // storage unavailable (private browsing, quota, ...) — just don't persist
  }
}

interface StoredView {
  ra: number;
  dec: number;
  fovDeg: number;
}

function readStoredView(): StoredView | null {
  try {
    const raw = localStorage.getItem(VIEW_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (typeof parsed.ra === 'number' && typeof parsed.dec === 'number' && typeof parsed.fovDeg === 'number') {
      return parsed;
    }
    return null;
  }
  catch {
    return null;
  }
}

/** Called from the same positionChanged/zoomChanged listener that already drives the FOV-overlay
 * redraw — so panning/zooming the map (or "Follow mount" moving it) keeps this up to date without
 * a separate polling loop, and reloading the page resumes exactly where it was left instead of
 * always resetting to a fixed target. */
function saveCurrentView(aladin: any) {
  try {
    const [ra, dec] = aladin.getRaDec();
    const [fovDeg] = aladin.getFov();
    const view: StoredView = { ra, dec, fovDeg };
    localStorage.setItem(VIEW_KEY, JSON.stringify(view));
  }
  catch {
    // storage unavailable, or aladin not fully initialized yet — skip this save
  }
}

/** Builds the Aladin image-survey object for a SURVEYS entry. Used both for the initial aladin()
 * call and for later switches, so the default survey never has to be swapped in after an initial
 * builtin one — that would otherwise briefly hit alasky/CDS for properties/MocServer/tiles before
 * being replaced. `A.imageHiPS` works without an aladin instance.
 * The custom URL is resolved to absolute: Aladin only recognizes it as a real HiPS location via
 * `new URL(...)`; a relative path fails that check and falls back to querying CDS's MocServer to
 * guess a matching public HiPS ID before it ever tries our proxy. */
function buildImageSurvey(survey: SurveyOption) {
  if (survey.custom) {
    const url = new URL(survey.custom.url, window.location.origin).href;
    return window.A.imageHiPS(url, {
      name: survey.label,
      cooFrame: survey.custom.frame,
      maxOrder: survey.custom.order,
      imgFormat: 'png',
    });
  }
  return survey.builtin;
}

export function SkyMapCard({ mountCoords, activeJob, fov, pa, lastImageFilename }: Props) {
  const cardRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const overlayImgRef = useRef<HTMLImageElement>(null);
  const aladinRef = useRef<any>(null);
  const mountCatalogRef = useRef<any>(null);
  const targetCatalogRef = useRef<any>(null);
  const fovOverlayRef = useRef<any>(null);
  const planningFovOverlayRef = useRef<any>(null);
  const ngcCatalogRef = useRef<any>(null);
  const sh2CatalogRef = useRef<any>(null);
  const ngcBoundaryRef = useRef<any>(null);
  const sh2BoundaryRef = useRef<any>(null);
  // Screen-space wrapper <div> per footprint (see positionFootprintImage) rather than an Aladin
  // catalog — this way the actual thumbnail pixels ARE the boundary, positioned/rotated/sized
  // exactly like the "last image" overlay already does for a single live capture, just for many
  // at once. The wrapper (rather than positioning the <img> directly) exists so a hidden
  // footprint can swap its content for a reveal button without disturbing the positioning code.
  const astrobinFootprintRefs = useRef<(HTMLDivElement | null)[]>([]);
  const astrobinFetchedRef = useRef(false);
  const appliedSurveyIdRef = useRef<string | null>(null);
  const [ready, setReady] = useState(false);
  const [surveyId, setSurveyId] = useState(SURVEYS[0].id);
  // Persisted across reloads (see FOLLOW_MOUNT_KEY/SHOW_LAST_IMAGE_KEY) — both are "set once,
  // forget about it" toggles, so a reload silently reverting them is more surprising than useful.
  const [showLastImage, setShowLastImage] = useState(() => readStoredBoolean(SHOW_LAST_IMAGE_KEY));
  const [followMount, setFollowMount] = useState(() => readStoredBoolean(FOLLOW_MOUNT_KEY));
  const [showNgc, setShowNgc] = useState(() => readStoredBoolean(SHOW_NGC_KEY));
  const [showSh2, setShowSh2] = useState(() => readStoredBoolean(SHOW_SH2_KEY));
  const [showAstrobin, setShowAstrobin] = useState(() => readStoredBoolean(SHOW_ASTROBIN_KEY));
  const [astrobinFootprints, setAstrobinFootprints] = useState<AstrobinFootprint[] | null>(null);
  // Wide-field shots otherwise sit permanently on top of any narrower-focal-length footprint of
  // the same area, since they're bigger and later in z-order — "hidden" collapses one down to
  // just its outline (plus a small reveal button) so whatever's underneath becomes clickable.
  // Not persisted: it's a per-session decluttering aid, not a setting worth remembering forever.
  const [hiddenAstrobinUrls, setHiddenAstrobinUrls] = useState<Set<string>>(new Set());
  const [astrobinPopover, setAstrobinPopover] = useState<{
    footprint: AstrobinFootprint; date: string | null; loading: boolean; error: boolean; x: number; y: number;
  } | null>(null);
  const astrobinPopoverRef = useRef<HTMLDivElement>(null);
  const [lastImageStretch, setLastImageStretch] = useState<StretchSettings>(DEFAULT_STRETCH);
  const [isFullscreen, setIsFullscreen] = useState(false);
  // A user-set, equipment-independent FOV rectangle for planning framing — always centered on
  // whatever the map is currently looking at (see fovCorners' caller in redraw()), unlike the
  // live FOV rectangle above which tracks the mount and only exists once it's actually slewed
  // somewhere. Lets you pan around and preview "would this target fit?" before committing to it.
  const [planningFovEnabled, setPlanningFovEnabled] = useState(() => readStoredBoolean(PLANNING_FOV_ENABLED_KEY));
  // Width/height in arcmin are derived (see sensorFovArcmin below) from these four equipment
  // factors instead of being entered directly — matches how you'd actually plan a shot ("what
  // does my camera+scope combo see"), and updates immediately if you're comparing focal lengths.
  const [sensorWidthPx, setSensorWidthPx] = useState(() => readStoredNumber(PLANNING_FOV_SENSOR_WIDTH_KEY, DEFAULT_SENSOR_WIDTH_PX));
  const [sensorHeightPx, setSensorHeightPx] = useState(() => readStoredNumber(PLANNING_FOV_SENSOR_HEIGHT_KEY, DEFAULT_SENSOR_HEIGHT_PX));
  const [pixelSizeUm, setPixelSizeUm] = useState(() => readStoredNumber(PLANNING_FOV_PIXEL_SIZE_KEY, DEFAULT_PIXEL_SIZE_UM));
  const [focalLengthMm, setFocalLengthMm] = useState(() => readStoredNumber(PLANNING_FOV_FOCAL_LENGTH_KEY, DEFAULT_FOCAL_LENGTH_MM));
  const [planningFovRotationDeg, setPlanningFovRotationDeg] = useState(() => readStoredNumber(PLANNING_FOV_ROTATION_KEY, 0));
  const [sensorConfigOpen, setSensorConfigOpen] = useState(false);
  const sensorConfigRef = useRef<HTMLDivElement>(null);
  const planningFovWidthArcmin = sensorFovArcmin(sensorWidthPx, pixelSizeUm, focalLengthMm);
  const planningFovHeightArcmin = sensorFovArcmin(sensorHeightPx, pixelSizeUm, focalLengthMm);
  // On-demand (not re-queried on every pan/zoom, unlike the FOV rectangles) — a SIMBAD conesearch
  // is a real network round-trip, and "what's in this exact framing" is naturally a "I've settled
  // on a spot, now check it" action rather than something to hammer continuously while dragging.
  const [fovObjects, setFovObjects] = useState<SimbadFovObject[] | null>(null);
  const [fovObjectsLoading, setFovObjectsLoading] = useState(false);
  const [fovObjectsError, setFovObjectsError] = useState(false);
  const [fovResultsOpen, setFovResultsOpen] = useState(false);
  const fovResultsRef = useRef<HTMLDivElement>(null);

  // Same click-outside/Escape convention as the sensor-settings popup above.
  useEffect(() => {
    if (!fovResultsOpen) return undefined;
    function onPointerDown(e: PointerEvent) {
      if (fovResultsRef.current && !fovResultsRef.current.contains(e.target as Node)) {
        setFovResultsOpen(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setFovResultsOpen(false);
    }
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [fovResultsOpen]);

  function searchFovObjects() {
    const aladin = aladinRef.current;
    if (!aladin) return;
    setFovResultsOpen(true);
    setFovObjectsLoading(true);
    setFovObjectsError(false);
    setFovObjects(null);
    const [centerRa, centerDec] = aladin.getRaDec();
    const widthDeg = planningFovWidthArcmin / 60;
    const heightDeg = planningFovHeightArcmin / 60;
    const radiusDeg = Math.hypot(widthDeg, heightDeg) / 2;
    window.A.catalogFromSimbad(
      { ra: centerRa, dec: centerDec },
      radiusDeg,
      { limit: 500 },
      (cat: any) => {
        setFovObjects(findFovObjects(cat.getSources(), centerRa, centerDec, widthDeg, heightDeg, planningFovRotationDeg));
        setFovObjectsLoading(false);
      },
      () => {
        setFovObjectsError(true);
        setFovObjectsLoading(false);
      },
    );
  }

  function goToFovObject(obj: SimbadFovObject) {
    aladinRef.current?.gotoRaDec(obj.ra, obj.dec);
  }

  // Opens the tab synchronously (within the click's own call stack) and points it once the URL
  // is ready — CompressionStream is async, and popup blockers kill window.open() calls made after
  // an await since they no longer look like a direct response to the user's gesture.
  function openAstrobinCoordsSearch(obj: SimbadFovObject) {
    const win = window.open('', '_blank');
    const radiusDeg = Math.max(0.25, Number.isFinite(obj.sizeArcmin) ? obj.sizeArcmin / 60 / 2 : 0.25);
    astrobinCoordsSearchUrl(obj.ra, obj.dec, radiusDeg).then((url) => {
      if (win) win.location.href = url;
    });
  }

  // Closes on an outside click or Escape — there's no existing popover convention elsewhere in
  // this app to match, so this is the plain/standard version of that pattern.
  useEffect(() => {
    if (!sensorConfigOpen) return undefined;
    function onPointerDown(e: PointerEvent) {
      if (sensorConfigRef.current && !sensorConfigRef.current.contains(e.target as Node)) {
        setSensorConfigOpen(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setSensorConfigOpen(false);
    }
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [sensorConfigOpen]);

  // Same click-outside/Escape convention as the sensor-settings popup above.
  useEffect(() => {
    if (!astrobinPopover) return undefined;
    function onPointerDown(e: PointerEvent) {
      if (astrobinPopoverRef.current && !astrobinPopoverRef.current.contains(e.target as Node)) {
        setAstrobinPopover(null);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setAstrobinPopover(null);
    }
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [astrobinPopover]);

  function openAstrobinPopover(f: AstrobinFootprint, e: React.MouseEvent) {
    const container = containerRef.current;
    const containerRect = container?.getBoundingClientRect();
    // Anchored to the click rather than a fixed corner — with dozens of footprints on screen at
    // once (see hiddenAstrobinUrls above), a fixed-corner popup ends up far from whatever was
    // actually clicked. Clamped against the map's own bounds (not the popover's real rendered
    // size, which isn't known until after it paints) so it can't run off the edge of the map.
    const x = containerRect ? clamp(e.clientX - containerRect.left, 0, containerRect.width - ASTROBIN_POPOVER_WIDTH) : 0;
    const y = containerRect ? clamp(e.clientY - containerRect.top, 0, containerRect.height - ASTROBIN_POPOVER_HEIGHT_ESTIMATE) : 0;
    setAstrobinPopover({ footprint: f, date: null, loading: true, error: false, x, y });
    fetchAstrobinImageDetail(f.hash)
      .then((detail) => {
        setAstrobinPopover((prev) => (prev?.footprint.url === f.url ? { ...prev, date: detail.date, loading: false } : prev));
      })
      .catch(() => {
        setAstrobinPopover((prev) => (prev?.footprint.url === f.url ? { ...prev, loading: false, error: true } : prev));
      });
  }

  function toggleAstrobinHidden(url: string) {
    setHiddenAstrobinUrls((prev) => {
      const next = new Set(prev);
      if (next.has(url)) next.delete(url); else next.add(url);
      return next;
    });
  }

  useEffect(() => {
    if (!window.A || !containerRef.current) return;
    window.A.init.then(() => {
      const defaultSurvey = SURVEYS[0];
      const savedView = readStoredView();
      const aladin = window.A.aladin(containerRef.current, {
        survey: buildImageSurvey(defaultSurvey),
        fov: savedView?.fovDeg ?? DEFAULT_FOV_DEG,
        target: savedView ? `${savedView.ra} ${savedView.dec}` : '0 +0',
        cooFrame: 'equatorial',
        showFullscreenControl: false,
        log: false,
      });
      aladinRef.current = aladin;
      appliedSurveyIdRef.current = defaultSurvey.id;

      const mountCat = window.A.catalog({ name: 'mount', sourceSize: 20, color: '#4ade80' });
      const targetCat = window.A.catalog({ name: 'target', sourceSize: 20, color: '#f59e0b' });
      aladin.addCatalog(mountCat);
      aladin.addCatalog(targetCat);
      mountCatalogRef.current = mountCat;
      targetCatalogRef.current = targetCat;

      const fovOverlay = window.A.graphicOverlay({ color: '#38bdf8', lineWidth: 2 });
      aladin.addOverlay(fovOverlay);
      fovOverlayRef.current = fovOverlay;

      // Dashed + a different hue than the live FOV overlay, so "planned framing" is never
      // mistaken for "where the camera is actually pointed right now".
      const planningFovOverlay = window.A.graphicOverlay({ color: '#c084fc', lineWidth: 2, lineDash: [8, 6] });
      aladin.addOverlay(planningFovOverlay);
      planningFovOverlayRef.current = planningFovOverlay;

      setReady(true);
    });
  }, []);

  useEffect(() => {
    if (!ready || !aladinRef.current || appliedSurveyIdRef.current === surveyId) return;
    appliedSurveyIdRef.current = surveyId;
    const survey = SURVEYS.find((s) => s.id === surveyId) ?? SURVEYS[0];
    aladinRef.current.setImageSurvey(buildImageSurvey(survey));
  }, [ready, surveyId]);

  useEffect(() => {
    if (!ready || !mountCatalogRef.current) return;
    mountCatalogRef.current.removeAll();
    if (mountCoords) {
      const raDeg = mountCoords.ra * 15;
      mountCatalogRef.current.addSources([
        window.A.marker(raDeg, mountCoords.dec, {
          popupTitle: 'Mount',
          popupDesc: `RA ${mountCoords.ra.toFixed(3)}h DEC ${mountCoords.dec.toFixed(3)}°`,
        }),
      ]);
    }
  }, [ready, mountCoords?.ra, mountCoords?.dec]);

  useEffect(() => {
    if (!ready || !targetCatalogRef.current) return;
    targetCatalogRef.current.removeAll();
    if (activeJob) {
      const raDeg = activeJob.targetRA * 15;
      targetCatalogRef.current.addSources([
        window.A.marker(raDeg, activeJob.targetDEC, { popupTitle: activeJob.name, popupDesc: 'Scheduler target' }),
      ]);
    }
  }, [ready, activeJob?.name, activeJob?.targetRA, activeJob?.targetDEC]);

  // The FOV rectangle (sky-registered polygon) and the last-image screen overlay (plain CSS,
  // since Aladin's image layers need real WCS — our capture previews have none) share the same
  // corner math, recomputed whenever the mount moves, the FOV changes, or the view pans/zooms.
  // redrawRef always holds the latest closure over current props; the effect below that actually
  // registers Aladin's positionChanged/zoomChanged listeners only depends on `ready`, so it runs
  // exactly once per mount instead of re-adding a listener pair on every mountCoords/fov update
  // (roughly once a second while Ekos runs). Aladin Lite v3 has no .on() counterpart to remove a
  // listener, so re-registering on every update used to leak one more pair forever — over a
  // multi-hour session that accumulated thousands of stale callbacks on the same long-lived Aladin
  // instance, and the next positionChanged/zoomChanged (e.g. one last mount update as Ekos stops)
  // fired all of them synchronously, which was enough to freeze the tab or lose the WebGL context.
  const redrawRef = useRef<() => void>(() => {});

  useEffect(() => {
    redrawRef.current = function redraw() {
      const aladin = aladinRef.current;
      const overlay = fovOverlayRef.current;
      if (!aladin || !overlay) return;

      const planningOverlay = planningFovOverlayRef.current;
      if (planningOverlay) {
        planningOverlay.removeAll();
        if (planningFovEnabled) {
          const [centerRa, centerDec] = aladin.getRaDec();
          const corners = fovCorners(
            centerRa,
            centerDec,
            planningFovWidthArcmin / 60,
            planningFovHeightArcmin / 60,
            planningFovRotationDeg,
          );
          planningOverlay.add(window.A.polygon(corners));
        }
      }

      if (showAstrobin && astrobinFootprints) {
        astrobinFootprints.forEach((f, i) => {
          const el = astrobinFootprintRefs.current[i];
          if (!el) return;
          // Only the fovCorners()-derived fallback (no real corners for this image) needs the
          // same +180° correction the live-capture overlay does — see positionFootprintImage.
          positionFootprintImage(el, aladin, footprintCorners(f), !f.corners);
        });
      } else {
        astrobinFootprintRefs.current.forEach((el) => { if (el) el.style.display = 'none'; });
      }

      overlay.removeAll();
      if (!mountCoords || !fov) {
        if (overlayImgRef.current) overlayImgRef.current.style.display = 'none';
        return;
      }

      const raDeg = mountCoords.ra * 15;
      const corners = fovCorners(raDeg, mountCoords.dec, fov.widthArcmin / 60, fov.heightArcmin / 60, pa ?? 0);
      overlay.add(window.A.polygon(corners));

      if (overlayImgRef.current && showLastImage && lastImageFilename) {
        positionFootprintImage(overlayImgRef.current, aladin, corners, true);
      } else if (overlayImgRef.current) {
        overlayImgRef.current.style.display = 'none';
      }
    };

    redrawRef.current();
  }, [
    mountCoords?.ra, mountCoords?.dec, fov?.widthArcmin, fov?.heightArcmin, pa, showLastImage, lastImageFilename,
    showAstrobin, astrobinFootprints,
    planningFovEnabled, planningFovWidthArcmin, planningFovHeightArcmin, planningFovRotationDeg,
  ]);

  useEffect(() => {
    if (!ready) return;
    const aladin = aladinRef.current;
    const onChange = () => {
      redrawRef.current();
      saveCurrentView(aladin);
    };
    aladin.on('positionChanged', onChange);
    aladin.on('zoomChanged', onChange);

    // Aladin's 'zoomChanged' callback only fires from explicit zoom calls (the +/- buttons,
    // setZoomFactor, etc.) — mouse-wheel zooming animates the field of view frame-by-frame without
    // ever going through the internal updateZoomState() that triggers it, so footprints/overlays
    // would otherwise sit stale (at their pre-zoom size/position) until the next pan. Polling
    // getFov() every animation frame is cheap (one float compare) next to the WebGL redraw Aladin
    // is already doing at the same rate, and catches that case too.
    let lastFov = aladin.getFov()[0];
    let frameId = requestAnimationFrame(function pollFov() {
      const fov = aladin.getFov()[0];
      if (fov !== lastFov) {
        lastFov = fov;
        onChange();
      }
      frameId = requestAnimationFrame(pollFov);
    });
    return () => cancelAnimationFrame(frameId);
  }, [ready]);

  // Keeps the view centered on the mount as it moves, instead of a one-shot "center now" click.
  useEffect(() => {
    if (!ready || !followMount || !mountCoords || !aladinRef.current) return;
    aladinRef.current.gotoRaDec(mountCoords.ra * 15, mountCoords.dec);
  }, [ready, followMount, mountCoords?.ra, mountCoords?.dec]);

  useEffect(() => {
    writeStoredBoolean(FOLLOW_MOUNT_KEY, followMount);
  }, [followMount]);

  useEffect(() => {
    writeStoredBoolean(SHOW_LAST_IMAGE_KEY, showLastImage);
  }, [showLastImage]);

  useEffect(() => {
    writeStoredBoolean(SHOW_NGC_KEY, showNgc);
  }, [showNgc]);

  useEffect(() => {
    writeStoredBoolean(SHOW_SH2_KEY, showSh2);
  }, [showSh2]);

  useEffect(() => {
    writeStoredBoolean(SHOW_ASTROBIN_KEY, showAstrobin);
  }, [showAstrobin]);

  useEffect(() => {
    writeStoredBoolean(PLANNING_FOV_ENABLED_KEY, planningFovEnabled);
  }, [planningFovEnabled]);

  useEffect(() => {
    writeStoredNumber(PLANNING_FOV_SENSOR_WIDTH_KEY, sensorWidthPx);
  }, [sensorWidthPx]);

  useEffect(() => {
    writeStoredNumber(PLANNING_FOV_SENSOR_HEIGHT_KEY, sensorHeightPx);
  }, [sensorHeightPx]);

  useEffect(() => {
    writeStoredNumber(PLANNING_FOV_PIXEL_SIZE_KEY, pixelSizeUm);
  }, [pixelSizeUm]);

  useEffect(() => {
    writeStoredNumber(PLANNING_FOV_FOCAL_LENGTH_KEY, focalLengthMm);
  }, [focalLengthMm]);

  useEffect(() => {
    writeStoredNumber(PLANNING_FOV_ROTATION_KEY, planningFovRotationDeg);
  }, [planningFovRotationDeg]);

  // Both catalogs are fetched at most once (lazily, on first enable) and then just shown/hidden —
  // a 180° cone search already covers the whole sky regardless of where it's centered, so there's
  // never a reason to re-query VizieR as the view pans or zooms.
  useEffect(() => {
    if (!ready || !aladinRef.current) return;
    if (ngcCatalogRef.current) {
      const action = showNgc ? 'show' : 'hide';
      ngcCatalogRef.current[action]();
      ngcBoundaryRef.current?.[action]();
      return;
    }
    if (!showNgc) return;
    const aladin = aladinRef.current;
    const [ra, dec] = aladin.getRaDec();
    window.A.catalogFromVizieR(
      NGC_VIZIER_CAT,
      { ra, dec },
      OVERLAY_CATALOG_RADIUS_DEG,
      { onClick: 'showTable', shape: 'circle', sourceSize: 4, color: '#facc15', name: 'NGC/IC', limit: 20000 },
      (cat: any) => {
        ngcCatalogRef.current = cat;
        aladin.addCatalog(cat);
        ngcBoundaryRef.current = buildBoundaryOverlay(aladin, cat, 'size', '#facc15');
      },
    );
  }, [ready, showNgc]);

  useEffect(() => {
    if (!ready || !aladinRef.current) return;
    if (sh2CatalogRef.current) {
      const action = showSh2 ? 'show' : 'hide';
      sh2CatalogRef.current[action]();
      sh2BoundaryRef.current?.[action]();
      return;
    }
    if (!showSh2) return;
    const aladin = aladinRef.current;
    const [ra, dec] = aladin.getRaDec();
    window.A.catalogFromVizieR(
      SH2_VIZIER_CAT,
      { ra, dec },
      OVERLAY_CATALOG_RADIUS_DEG,
      { onClick: 'showTable', shape: 'circle', sourceSize: 4, color: '#fb7185', name: 'Sh2', limit: 1000 },
      (cat: any) => {
        sh2CatalogRef.current = cat;
        aladin.addCatalog(cat);
        sh2BoundaryRef.current = buildBoundaryOverlay(aladin, cat, 'Diam', '#fb7185');
      },
    );
  }, [ready, showSh2]);

  // Fetched at most once (lazily, on first enable) from our own server-side cache — see
  // fetchAstrobinFootprints — then just shown/hidden via redraw()'s showAstrobin check.
  useEffect(() => {
    if (!showAstrobin || astrobinFetchedRef.current) return;
    astrobinFetchedRef.current = true;
    fetchAstrobinFootprints()
      .then(setAstrobinFootprints)
      .catch(() => { /* AstroBin unreachable — leave the toggle checked but nothing drawn, no retry loop */ });
  }, [showAstrobin]);

  useEffect(() => {
    function onFullscreenChange() {
      setIsFullscreen(document.fullscreenElement === cardRef.current);
    }
    document.addEventListener('fullscreenchange', onFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
  }, []);

  function toggleFullscreen() {
    if (document.fullscreenElement === cardRef.current) {
      document.exitFullscreen();
    } else {
      cardRef.current?.requestFullscreen();
    }
  }

  // The overlay used DEFAULT_STRETCH (a no-op linear passthrough) unconditionally, so "last
  // image" always rendered essentially unstretched instead of matching what the image strip
  // shows for the same file. Cached per filename like ImageStrip's own requests — a 404 (e.g. a
  // since-deleted file) must never be retried on every status push.
  const requestedStretchFile = useRef<string | null>(null);
  useEffect(() => {
    if (!lastImageFilename || requestedStretchFile.current === lastImageFilename) return;
    requestedStretchFile.current = lastImageFilename;
    fetchAutoStretch(lastImageFilename, false)
      .then(setLastImageStretch)
      .catch(() => { /* leave the previous stretch in place, no retry */ });
  }, [lastImageFilename]);

  return (
    <div ref={cardRef} className="card card--wide">
      <h3>Sky Map</h3>
      <div className="sky-map-controls">
        <select value={surveyId} onChange={(e) => setSurveyId(e.target.value)}>
          {SURVEYS.map((s) => (
            <option key={s.id} value={s.id}>{s.label}</option>
          ))}
        </select>
        <button
          type="button"
          className="sky-map-icon-button"
          onClick={toggleFullscreen}
          title={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}
          aria-label={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}
        >
          {isFullscreen ? <CompressIcon /> : <ExpandIcon />}
        </button>
        <div className="sky-map-toggles">
          <label className="sky-map-toggle">
            <input
              type="checkbox"
              checked={followMount}
              onChange={(e) => setFollowMount(e.target.checked)}
              disabled={!mountCoords}
            />
            Follow mount
          </label>
          <label className="sky-map-toggle">
            <input
              type="checkbox"
              checked={showLastImage}
              onChange={(e) => setShowLastImage(e.target.checked)}
              disabled={!lastImageFilename}
            />
            Show last image
          </label>
          <label className="sky-map-toggle">
            <input type="checkbox" checked={showNgc} onChange={(e) => setShowNgc(e.target.checked)} />
            NGC/IC
          </label>
          <label className="sky-map-toggle">
            <input type="checkbox" checked={showSh2} onChange={(e) => setShowSh2(e.target.checked)} />
            Sharpless (Sh2)
          </label>
          <label className="sky-map-toggle">
            <input type="checkbox" checked={showAstrobin} onChange={(e) => setShowAstrobin(e.target.checked)} />
            My AstroBin
          </label>
          <label className="sky-map-toggle">
            <input
              type="checkbox"
              checked={planningFovEnabled}
              onChange={(e) => setPlanningFovEnabled(e.target.checked)}
            />
            Planning FOV
          </label>
        </div>
      </div>
      {planningFovEnabled && (
        <div className="sky-map-planning-fov">
          <div className="sky-map-sensor-config" ref={sensorConfigRef}>
            <button
              type="button"
              className="sky-map-icon-button"
              onClick={() => setSensorConfigOpen((open) => !open)}
              title="Sensor settings"
              aria-label="Sensor settings"
            >
              <SlidersIcon />
            </button>
            {sensorConfigOpen && (
              <div className="sky-map-sensor-popup">
                <label>
                  Sensor
                  <input
                    type="number" min={1} step={1} value={sensorWidthPx}
                    onChange={(e) => setSensorWidthPx(Number(e.target.value))}
                  />
                  ×
                  <input
                    type="number" min={1} step={1} value={sensorHeightPx}
                    onChange={(e) => setSensorHeightPx(Number(e.target.value))}
                  />
                  px
                </label>
                <label>
                  Pixel size
                  <input
                    type="number" min={0.1} step={0.01} value={pixelSizeUm}
                    onChange={(e) => setPixelSizeUm(Number(e.target.value))}
                  />
                  µm
                </label>
                <label>
                  Focal length
                  <input
                    type="number" min={1} step={1} value={focalLengthMm}
                    onChange={(e) => setFocalLengthMm(Number(e.target.value))}
                  />
                  mm
                </label>
              </div>
            )}
          </div>
          <label>
            Rotation
            <input
              type="number" step={1} value={planningFovRotationDeg}
              onChange={(e) => setPlanningFovRotationDeg(Number(e.target.value))}
            />
            °
          </label>
          <span className="sky-map-planning-fov-result">
            → {planningFovWidthArcmin.toFixed(1)}&apos; × {planningFovHeightArcmin.toFixed(1)}&apos;
            {' '}({(planningFovWidthArcmin / 60).toFixed(2)}° × {(planningFovHeightArcmin / 60).toFixed(2)}°)
          </span>
          <div className="sky-map-fov-results-anchor" ref={fovResultsRef}>
            <button type="button" onClick={searchFovObjects} disabled={fovObjectsLoading}>
              {fovObjectsLoading ? 'Searching…' : 'Find objects in FOV'}
            </button>
            {fovResultsOpen && (
              <div className="sky-map-fov-results-popup">
                {fovObjectsError && <div className="sky-map-fov-objects-empty">SIMBAD search failed — try again</div>}
                {fovObjectsLoading && <div className="sky-map-fov-objects-empty">Searching SIMBAD…</div>}
                {fovObjects && (
                  fovObjects.length === 0 ? (
                    <div className="sky-map-fov-objects-empty">No nebulae, remnants, or clusters found in this frame</div>
                  ) : (
                    <ul className="sky-map-fov-objects">
                      {fovObjects.map((obj) => (
                        <li key={obj.name}>
                          <button type="button" className="sky-map-fov-object-goto" onClick={() => goToFovObject(obj)} title="Center on this object">
                            <span className="sky-map-fov-object-name">{obj.name}</span>
                            <span className="sky-map-fov-object-type">{obj.typeLabel}</span>
                            {Number.isFinite(obj.sizeArcmin) && (
                              <span className="sky-map-fov-object-size">{obj.sizeArcmin.toFixed(0)}&apos;</span>
                            )}
                          </button>
                          <span className="sky-map-fov-object-astrobin">
                            AstroBin:
                            <a href={astrobinSearchUrl(obj.name)} target="_blank" rel="noreferrer">Name</a>
                            <button type="button" onClick={() => openAstrobinCoordsSearch(obj)} title="AstroBin search by coordinates (requires AstroBin Ultimate)">
                              Coords
                            </button>
                          </span>
                        </li>
                      ))}
                    </ul>
                  )
                )}
              </div>
            )}
          </div>
        </div>
      )}
      <div ref={containerRef} className="sky-map">
        {lastImageFilename && (
          <img
            ref={overlayImgRef}
            src={imageUrl(lastImageFilename, 600, lastImageStretch)}
            alt="Last capture"
            className="sky-map-last-image"
          />
        )}
        {showAstrobin && astrobinFootprints?.map((f, i) => {
          const hidden = hiddenAstrobinUrls.has(f.url);
          return (
            <div
              key={f.url}
              ref={(el) => { astrobinFootprintRefs.current[i] = el; }}
              className={hidden ? 'sky-map-astrobin-footprint sky-map-astrobin-footprint--hidden' : 'sky-map-astrobin-footprint'}
            >
              {hidden ? (
                <button
                  type="button"
                  className="sky-map-astrobin-reveal"
                  title={f.title}
                  onClick={(e) => openAstrobinPopover(f, e)}
                >
                  <GearIcon />
                </button>
              ) : (
                <img
                  src={f.thumbnailUrl}
                  alt={f.title}
                  title={f.title}
                  loading="lazy"
                  className="sky-map-astrobin-thumb"
                  onClick={(e) => openAstrobinPopover(f, e)}
                />
              )}
            </div>
          );
        })}
        {astrobinPopover && (
          <div
            className="sky-map-astrobin-popover"
            ref={astrobinPopoverRef}
            style={{ left: astrobinPopover.x, top: astrobinPopover.y }}
          >
            <button type="button" className="sky-map-astrobin-popover-close" onClick={() => setAstrobinPopover(null)} aria-label="Close">×</button>
            <div className="sky-map-astrobin-popover-title">{astrobinPopover.footprint.title}</div>
            <div className="sky-map-astrobin-popover-date">
              {astrobinPopover.loading ? 'Loading…' : astrobinPopover.error ? 'Failed to load date' : (astrobinPopover.date ?? 'No acquisition date')}
            </div>
            <div className="sky-map-astrobin-popover-actions">
              <a href={astrobinPopover.footprint.url} target="_blank" rel="noreferrer">Open on AstroBin</a>
              <button type="button" onClick={() => toggleAstrobinHidden(astrobinPopover.footprint.url)}>
                {hiddenAstrobinUrls.has(astrobinPopover.footprint.url) ? 'Show' : 'Hide'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
