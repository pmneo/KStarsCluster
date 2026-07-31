import { useEffect, useRef, useState } from 'react';
import type { SchedulerJob } from '../api/types';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import { altAzToRaDec, raDecToAltAz } from '../api/coordinates';
import {
  fetchObservatoryInfo, fetchArtificialHorizon, isValidLocation, TERRAIN_IMAGE_URL,
  type ObservatoryInfo, type ArtificialHorizonRegion,
} from '../api/horizonApi';

// Aladin Lite v3 is loaded via <script> in index.html, not bundled — it ships no official types.
declare global {
  interface Window {
    A: any;
  }
}

/** Shared by a single grid/loop pass — counts how many projection calls actually threw (as
 * opposed to legitimately returning null for an off-screen point), so a caller whose whole grid
 * came back empty can tell "nothing here is on-screen right now" apart from "the WebGL texture
 * state was transiently broken for this entire attempt" and retry only the latter (see
 * terrainDebounceRef's retry loop). */
interface ProjectionStats { exceptions: number; }

/** aladin.world2pix/pix2world don't just return null for a point their current projection can't
 * handle (already handled everywhere below) — under some internal states (observed alongside a
 * "Tex image ... incurring lazy initialization" WebGL warning, so likely a HiPS tile texture not
 * fully ready yet, typically right after a zoom/pan brings new tiles into view) they throw
 * outright instead ("can't access property Symbol.iterator, i is undefined"), which none of our
 * own null-checks can catch since the exception happens inside Aladin's own code before it ever
 * returns. Every call site here goes through these wrappers so one bad projection this redraw
 * can't ever take down the whole grid/loop it's part of. */
function safeWorld2Pix(aladin: any, ra: number, dec: number, stats?: ProjectionStats): [number, number] | null {
  try {
    return aladin.world2pix(ra, dec) ?? null;
  }
  catch {
    if (stats) stats.exceptions++;
    return null;
  }
}

function safePix2World(aladin: any, x: number, y: number, stats?: ProjectionStats): [number, number] | null {
  try {
    return aladin.pix2world(x, y) ?? null;
  }
  catch {
    if (stats) stats.exceptions++;
    return null;
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

interface ScreenRect {
  cx: number;
  cy: number;
  w: number;
  h: number;
  angleRad: number;
}

/** Projects a sky-registered rectangle (world corners in [top-left, top-right, bottom-right,
 * bottom-left] winding order) to screen-space pixels — center, size, and rotation — shared by
 * both the DOM-positioned "last image" overlay (positionFootprintImage below) and the
 * canvas-drawn AstroBin footprints (drawAstrobinFootprints).
 *
 * `extraHalfTurn` exists because the two callers need opposite answers to the same question, and
 * there's no way to derive it from the corners alone: the live-capture caller's corners come from
 * fovCorners() using Ekos's own `pa`, which (empirically) needs a +180° correction to stop the
 * actual photo rendering upside down; AstroBin's corners are real solved RA/Dec per corner
 * (verified against a named object's true catalog position landing exactly where its pixel
 * position predicts), and adding that same +180° there just rotates a correct answer into a wrong
 * one — confirmed the hard way when it flipped every AstroBin footprint 180°, not just the
 * one-off mirrored-solve cases the corners were adopted to fix in the first place. */
function computeScreenRect(aladin: any, corners: [number, number][], extraHalfTurn: boolean): ScreenRect | null {
  const projected = corners.map(([ra, dec]) => safeWorld2Pix(aladin, ra, dec));
  // world2pix returns null/undefined for points its current projection can't map (e.g. an
  // AstroBin footprint on the opposite side of the sky from wherever the view happens to be) —
  // rather than crashing the whole redraw() (which would also skip the live FOV overlay below
  // it), just leave this one unrendered until it's somewhere projectable.
  if (projected.some((p) => !p)) return null;
  const px = projected as [number, number][];
  const cx = (px[0][0] + px[2][0]) / 2;
  const cy = (px[0][1] + px[2][1]) / 2;
  const w = Math.hypot(px[1][0] - px[0][0], px[1][1] - px[0][1]);
  const h = Math.hypot(px[2][0] - px[1][0], px[2][1] - px[1][1]);
  const angleRad = Math.atan2(px[1][1] - px[0][1], px[1][0] - px[0][0]) + (extraHalfTurn ? Math.PI : 0);
  return { cx, cy, w, h, angleRad };
}

/** Positions a plain screen-space <img> over a sky-registered rectangle via computeScreenRect —
 * the technique the live "last image" overlay uses, since Aladin's own image layers need real
 * HiPS/WCS tiling, which a one-off JPEG thumbnail doesn't have. AstroBin's own footprints use to
 * use this too (one absolutely-positioned <img> each), until there got to be enough of them that
 * a canvas (see drawAstrobinFootprints) was worth the switch — see the SkyMapCard performance
 * discussion for why. */
function positionFootprintImage(img: HTMLElement, aladin: any, corners: [number, number][], extraHalfTurn: boolean) {
  const rect = computeScreenRect(aladin, corners, extraHalfTurn);
  if (!rect) {
    img.style.display = 'none';
    return;
  }
  img.style.display = 'block';
  img.style.width = `${rect.w}px`;
  img.style.height = `${rect.h}px`;
  img.style.left = `${rect.cx}px`;
  img.style.top = `${rect.cy}px`;
  img.style.marginLeft = `${-rect.w / 2}px`;
  img.style.marginTop = `${-rect.h / 2}px`;
  img.style.transform = `rotate(${(rect.angleRad * 180) / Math.PI}deg)`;
}

const FOLLOW_MOUNT_KEY = 'skymap.followMount';
const SHOW_LAST_IMAGE_KEY = 'skymap.showLastImage';
const SHOW_NGC_KEY = 'skymap.showNgc';
const SHOW_SH2_KEY = 'skymap.showSh2';
const SHOW_ASTROBIN_KEY = 'skymap.showAstrobin';
const SHOW_HORIZON_KEY = 'skymap.showHorizon';
const SHOW_TERRAIN_KEY = 'skymap.showTerrain';
// Real width is CSS-defined (see .sky-map-astrobin-popover); the height is only an estimate since
// the actual rendered height depends on title wrapping and isn't known until after it paints —
// good enough for clamping the popover to stay on-screen without needing a post-paint measurement.
const ASTROBIN_POPOVER_WIDTH = 220;
const ASTROBIN_POPOVER_HEIGHT_ESTIMATE = 140;
// How far the mouse can move between down and up before a click is treated as a drag instead —
// see astrobinMouseDownRef.
const ASTROBIN_DRAG_CLICK_THRESHOLD_PX = 5;

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

/** Shoelace formula on the footprint's own corners, treating RA/Dec as planar — inaccurate as a
 * real deg² figure (no cos(dec) scaling, breaks near the RA=0/360 wrap) but every image in this
 * gallery is a few degrees across at most, so it's more than good enough to rank "which of these
 * two is the wider shot" for z-ordering below. */
function footprintAreaDeg2(f: AstrobinFootprint): number {
  if (!f.corners) return f.widthDeg * f.heightDeg;
  const pts = f.corners;
  let sum = 0;
  for (let i = 0; i < pts.length; i++) {
    const [x1, y1] = pts[i];
    const [x2, y2] = pts[(i + 1) % pts.length];
    sum += x1 * y2 - x2 * y1;
  }
  return Math.abs(sum) / 2;
}

/** Screen-space geometry of one drawn footprint, recomputed every redraw() and consumed by
 * hitTestAstrobinFootprint below — a canvas has no DOM nodes of its own to hang hover/click
 * listeners off, so hit-testing has to be done by hand against this list instead. */
interface AstrobinHitRect extends ScreenRect {
  footprint: AstrobinFootprint;
  hidden: boolean;
}

const ASTROBIN_GEAR_SIZE = 20;
const ASTROBIN_GEAR_MARGIN = 2;

/** Point-in-rotated-rectangle test: rotate the query point into the rectangle's own local
 * (unrotated) frame around its center, then it's a plain axis-aligned bounds check. */
function pointInRotatedRect(px: number, py: number, r: ScreenRect): [number, number] {
  const dx = px - r.cx;
  const dy = py - r.cy;
  const localX = dx * Math.cos(r.angleRad) + dy * Math.sin(r.angleRad);
  const localY = -dx * Math.sin(r.angleRad) + dy * Math.cos(r.angleRad);
  return [localX, localY];
}

/** The gear button's local position within its (hidden) footprint's own rotated frame — top-right
 * corner inset by ASTROBIN_GEAR_MARGIN, matching the old CSS `top: 2px; right: 2px`. Shared by the
 * draw and hit-test code so they can't drift apart. */
function astrobinGearCenter(r: ScreenRect): [number, number] {
  return [r.w / 2 - ASTROBIN_GEAR_MARGIN - ASTROBIN_GEAR_SIZE / 2, -r.h / 2 + ASTROBIN_GEAR_MARGIN + ASTROBIN_GEAR_SIZE / 2];
}

/** The rect's own bottom-right corner in screen space — not just cx+w/2,cy+h/2, since the box is
 * rotated and "bottom-right" has to mean whichever of its four corners is actually furthest
 * down-and-right on screen, not a corner that rotates along with the image itself (a popover
 * anchored to a rotating corner would swing around as the footprint's rotation angle carries it
 * — the whole point here is a stable anchor). */
function screenRectBottomRight(r: ScreenRect): [number, number] {
  const hw = r.w / 2;
  const hh = r.h / 2;
  const cos = Math.cos(r.angleRad);
  const sin = Math.sin(r.angleRad);
  const corners: [number, number][] = [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]];
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const [dx, dy] of corners) {
    maxX = Math.max(maxX, r.cx + dx * cos - dy * sin);
    maxY = Math.max(maxY, r.cy + dx * sin + dy * cos);
  }
  return [maxX, maxY];
}

/** Iterates hit rects in reverse (later entries paint on top — see drawAstrobinFootprints) so the
 * topmost thing under the cursor wins. Hidden footprints only expose their small gear button —
 * the canvas equivalent of the old wrapper's `pointer-events: none` — so the rest of their (still
 * wide-field-sized) box doesn't shadow whatever's underneath. */
function hitTestAstrobinFootprint(x: number, y: number, rects: AstrobinHitRect[]): { footprint: AstrobinFootprint; onGear: boolean } | null {
  for (let i = rects.length - 1; i >= 0; i--) {
    const r = rects[i];
    const [localX, localY] = pointInRotatedRect(x, y, r);
    if (r.hidden) {
      const [gx, gy] = astrobinGearCenter(r);
      if (Math.abs(localX - gx) <= ASTROBIN_GEAR_SIZE / 2 && Math.abs(localY - gy) <= ASTROBIN_GEAR_SIZE / 2) {
        return { footprint: r.footprint, onGear: true };
      }
      continue;
    }
    if (Math.abs(localX) <= r.w / 2 && Math.abs(localY) <= r.h / 2) {
      return { footprint: r.footprint, onGear: false };
    }
  }
  return null;
}

/** Loaded once per hash and reused across redraws/frames — plain Image objects rather than DOM
 * <img> elements, since these are only ever drawImage()'d onto the canvas, never inserted. No
 * crossOrigin needed either: nothing here reads pixels back (no getImageData/toDataURL), so
 * there's no canvas-tainting concern, and requesting one would just risk an extra failed preflight
 * against AstroBin's CDN. */
function getAstrobinImage(cache: Map<string, HTMLImageElement>, f: AstrobinFootprint, onLoad: () => void): HTMLImageElement {
  let img = cache.get(f.hash);
  if (!img) {
    img = new Image();
    img.onload = onLoad;
    img.src = f.thumbnailUrl;
    cache.set(f.hash, img);
  }
  return img;
}

/** Redraws the gear/settings icon by hand instead of rasterizing the old SVG — same geometry (8
 * teeth around a ring, viewBox 24x24 centered at 12,12), just emitted as canvas path calls
 * directly at whatever scale the button needs, rather than loading yet another image
 * asynchronously for something this simple. */
function drawGearButton(ctx: CanvasRenderingContext2D, gx: number, gy: number, size: number) {
  const half = size / 2;
  ctx.save();
  ctx.translate(gx, gy);
  ctx.fillStyle = 'rgba(15, 17, 26, 0.85)';
  ctx.strokeStyle = '#22d3ee';
  ctx.lineWidth = 1;
  ctx.fillRect(-half, -half, size, size);
  ctx.strokeRect(-half, -half, size, size);

  const s = (size * 0.7) / 24;
  ctx.fillStyle = '#22d3ee';
  for (let deg = 0; deg < 360; deg += 45) {
    ctx.save();
    ctx.rotate((deg * Math.PI) / 180);
    ctx.fillRect(-1.5 * s, -11.5 * s, 3 * s, 5 * s);
    ctx.restore();
  }
  ctx.lineWidth = 2 * s;
  ctx.beginPath();
  ctx.arc(0, 0, 7 * s, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(0, 0, 2.5 * s, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

function drawOneAstrobinFootprint(
  ctx: CanvasRenderingContext2D,
  f: AstrobinFootprint,
  rect: ScreenRect,
  hidden: boolean,
  isSelected: boolean,
  imagesCache: Map<string, HTMLImageElement>,
  onImageLoad: () => void,
) {
  const { cx, cy, w, h, angleRad } = rect;
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(angleRad);
  if (hidden) {
    ctx.strokeStyle = '#22d3ee';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 3]);
    ctx.strokeRect(-w / 2, -h / 2, w, h);
    ctx.setLineDash([]);
    const [gx, gy] = astrobinGearCenter(rect);
    drawGearButton(ctx, gx, gy, ASTROBIN_GEAR_SIZE);
  } else {
    const img = getAstrobinImage(imagesCache, f, onImageLoad);
    // opacity applies to the image AND its outline together, matching the old CSS behavior of
    // opacity on the whole footprint element.
    ctx.globalAlpha = isSelected ? 1 : 0.8;
    if (img.complete && img.naturalWidth > 0) {
      ctx.drawImage(img, -w / 2, -h / 2, w, h);
    }
    ctx.strokeStyle = '#22d3ee';
    ctx.lineWidth = 1;
    ctx.strokeRect(-w / 2, -h / 2, w, h);
    ctx.globalAlpha = 1;
  }
  ctx.restore();
}

/** Draws every currently-projectable footprint onto a single canvas instead of one absolutely-
 * positioned DOM element each — with a couple hundred images in a gallery, that used to mean a
 * couple hundred elements getting their transform/size recomputed on every pan/zoom frame; a
 * canvas redraw touches no layout at all. `footprints` is expected pre-sorted largest-first (see
 * its fetch call site) so a wide-field shot paints under any narrower one of the same target by
 * default; `selectedUrl` — whichever footprint currently has its popover open, see
 * handleAstrobinClick — is drawn last/on top regardless of its own size, replacing the transient
 * hover-raises-z-index behavior with one that stays put until the popover actually closes.
 * Returns the screen-space geometry of everything drawn, for the caller's own hit-testing. */
function drawAstrobinFootprints(
  ctx: CanvasRenderingContext2D,
  aladin: any,
  footprints: AstrobinFootprint[],
  hiddenUrls: Set<string>,
  selectedUrl: string | null,
  imagesCache: Map<string, HTMLImageElement>,
  onImageLoad: () => void,
): AstrobinHitRect[] {
  const rects: AstrobinHitRect[] = [];
  let selectedEntry: { footprint: AstrobinFootprint; rect: ScreenRect } | null = null;

  for (const footprint of footprints) {
    const hidden = hiddenUrls.has(footprint.url);
    const rect = computeScreenRect(aladin, footprintCorners(footprint), !footprint.corners);
    if (!rect) continue;
    rects.push({ footprint, hidden, ...rect });
    if (footprint.url === selectedUrl && !hidden) {
      selectedEntry = { footprint, rect };
      continue;
    }
    drawOneAstrobinFootprint(ctx, footprint, rect, hidden, false, imagesCache, onImageLoad);
  }
  if (selectedEntry) {
    drawOneAstrobinFootprint(ctx, selectedEntry.footprint, selectedEntry.rect, false, true, imagesCache, onImageLoad);
  }
  return rects;
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

/** Columns for the downsampled az/alt lookup grid drawTerrainOverlay samples the panorama at —
 * KStars' own TerrainRenderer does the same trick (compute az/alt for every Nth screen pixel,
 * upscale/interpolate the rest) since per-pixel az/alt is the expensive part; here the "upscale"
 * step is just letting the browser's own smoothed drawImage scale a tiny canvas up to full size.
 * 64 columns of a real photo just comes out as an unrecognizable color blur once upscaled to fill
 * the card — high enough to actually make out rooflines/trees. Only affordable once a pan/zoom
 * gesture has settled (see terrainDebounceRef) — during the gesture itself, TERRAIN_LIVE_SAMPLE_COLS
 * is used instead (see its own comment for why a live pass exists at all). */
const TERRAIN_SAMPLE_COLS = 320;

/** Drawn on *every* redraw() call, not debounced — without this, the terrain layer visibly stayed
 * at its pre-gesture framing for the entire zoom/pan (however slow or fast) and only snapped to
 * the new view ~120ms after the mouse stopped, while Aladin's own WebGL view already tracked the
 * gesture live; that read as "the sky map shrinks, our overlay doesn't". Deliberately much coarser
 * than TERRAIN_SAMPLE_COLS — this one runs at the browser's actual frame rate for the whole
 * duration of a gesture, not once after it settles, so it needs to be cheap enough that a fast
 * flick of the scroll wheel never reintroduces the jank the debounced high-res pass exists to
 * avoid; the debounced pass then sharpens it once things settle, same as before. */
const TERRAIN_LIVE_SAMPLE_COLS = 32;

/** Reprojects the user's "Terrain" panorama (an equirectangular Az/Alt photo, see
 * ObservatoryInfo/KStarsConfig's Terrain.* keys) onto the sky map's current view for the chosen
 * simulation time — the exact inverse of how the image was meant to be read: for each screen pixel
 * (downsampled), find what RA/Dec Aladin is showing there, convert that to Alt/Az for the chosen
 * time, then sample the source photo's pixel for that Alt/Az (see KStars' own
 * terrainrenderer.cpp::getPixel, which this mirrors exactly, correction offsets included). */
function drawTerrainOverlay(
  ctx: CanvasRenderingContext2D,
  aladin: any,
  containerW: number,
  containerH: number,
  terrainImg: HTMLImageElement,
  info: ObservatoryInfo,
  dateMs: number,
  stats: ProjectionStats,
  cols: number,
) {
  const imgW = terrainImg.naturalWidth;
  const imgH = terrainImg.naturalHeight;
  if (imgW === 0 || imgH === 0 || containerW === 0 || containerH === 0) return;

  const rows = Math.max(1, Math.round(cols * (containerH / containerW)));

  const offscreen = document.createElement('canvas');
  offscreen.width = cols;
  offscreen.height = rows;
  const octx = offscreen.getContext('2d');
  if (!octx) return;

  for (let j = 0; j < rows; j++) {
    const y = ((j + 0.5) / rows) * containerH;
    for (let i = 0; i < cols; i++) {
      const x = ((i + 0.5) / cols) * containerW;
      const world = safePix2World(aladin, x, y, stats);
      // Aladin returns null for a screen point outside the current projection's valid disk — but
      // right at that boundary (common when zoomed out far enough to see the whole sky at once)
      // it can instead return a "valid" array holding NaN, which !world doesn't catch and which
      // then poisons every downstream value (a single NaN drawImage() source coordinate is enough
      // to throw and abort the whole redraw — that's the reported "crashes on zoom out").
      if (!world || !Number.isFinite(world[0]) || !Number.isFinite(world[1])) continue;

      const { altDeg, azDeg } = raDecToAltAz(world[0], world[1], info.latitude, info.longitude, dateMs);
      const alt = altDeg - info.terrainCorrectAlt;
      if (alt < -90 || alt > 90) continue;

      let az = (((azDeg + info.terrainCorrectAz) % 360) + 360) % 360;
      if (az > 180) az -= 360;

      const pixX = Math.max(0, Math.min(imgW - 1, imgW / 2 + (az / 360) * imgW));
      const pixYFromBottom = Math.max(0, Math.min(imgH - 1, ((alt + 90) / 180) * imgH));
      const pixY = (imgH - 1) - pixYFromBottom;
      if (!Number.isFinite(pixX) || !Number.isFinite(pixY)) continue;

      octx.drawImage(terrainImg, pixX, pixY, 1, 1, i, j, 1, 1);
    }
  }

  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(offscreen, 0, 0, cols, rows, 0, 0, containerW, containerH);
}

/** Projects a closed loop of (RA, DEC) degree pairs to screen pixels via aladin.world2pix, one
 * per vertex — null wherever that vertex isn't currently projectable, exactly like the FOV
 * overlays' own computeScreenRect already handles per-corner. */
function projectLoop(
  aladin: any,
  points: [number, number][],
  stats?: ProjectionStats,
): ({ x: number; y: number } | null)[] {
  return points.map(([ra, dec]) => {
    const p = safeWorld2Pix(aladin, ra, dec, stats);
    return p ? { x: p[0], y: p[1] } : null;
  });
}

/** Strokes a closed loop of screen points, breaking into separate sub-paths wherever a vertex
 * didn't project (null) or the jump to it is implausibly large relative to the viewport (see
 * maxSegmentPx) — the loop only ever fully renders when the whole thing is in frame (e.g. zoomed
 * out to see the whole sky); otherwise whatever contiguous arc is currently visible still draws
 * correctly instead of the whole shape silently vanishing.
 *
 * Stroke-only: filling one of these sub-paths would implicitly close it with a straight line
 * straight from wherever the visible arc happens to end back to wherever it starts — for an arc
 * that's only a fraction of the true loop (the common case), that chord cuts across the screen at
 * whatever angle those two endpoints happen to define, which is exactly the "filled diagonally"
 * artifact an earlier version of this had. Regions are just outlined, not shaded, now anyway. */
function strokeHorizonLoop(
  ctx: CanvasRenderingContext2D,
  screenPoints: ({ x: number; y: number } | null)[],
  stroke: string,
  maxSegmentPx: number,
  lineWidth = 1.5,
) {
  const n = screenPoints.length;
  if (n < 2) return;

  const subpaths: { x: number; y: number }[][] = [];
  let current: { x: number; y: number }[] = [];
  let prev: { x: number; y: number } | null = null;
  // i <= n (not < n) revisits index 0 at the end, closing the loop when it stayed unbroken.
  for (let i = 0; i <= n; i++) {
    const pt = screenPoints[i % n];
    const jumpTooFar = !!(pt && prev && Math.hypot(pt.x - prev.x, pt.y - prev.y) > maxSegmentPx);
    if (!pt || jumpTooFar) {
      if (current.length > 1) subpaths.push(current);
      current = pt && jumpTooFar ? [pt] : [];
    } else {
      current.push(pt);
    }
    prev = pt;
  }
  if (current.length > 1) subpaths.push(current);

  for (const sub of subpaths) {
    ctx.beginPath();
    ctx.moveTo(sub[0].x, sub[0].y);
    for (let i = 1; i < sub.length; i++) ctx.lineTo(sub[i].x, sub[i].y);
    ctx.strokeStyle = stroke;
    ctx.lineWidth = lineWidth;
    ctx.stroke();
  }
}

/** Draws the flat geometric horizon (always available from lat/lon alone) plus any enabled
 * artificial-horizon regions, both reprojected in RA/Dec for the chosen simulation time. */
function drawHorizonOverlay(
  ctx: CanvasRenderingContext2D,
  aladin: any,
  info: ObservatoryInfo,
  regions: ArtificialHorizonRegion[],
  dateMs: number,
  containerW: number,
  containerH: number,
  stats?: ProjectionStats,
) {
  // A real jump between adjacent sample points never needs more than a fraction of the viewport
  // itself — anything longer means world2pix landed the far side of a wraparound rather than
  // somewhere actually adjacent on screen (see strokeHorizonLoop). A fixed pixel constant here
  // instead of scaling to the viewport used to make this check nearly unreachable (4000px, when
  // the canvas itself is only a few hundred px), which is exactly how the FOV-180° diagonal chord
  // (a `world2pix`-succeeds-but-lands-absurdly artifact, not a redraw failure) got through unbroken.
  const maxSegmentPx = Math.max(containerW, containerH) * 0.6;

  const flatPoints: [number, number][] = [];
  for (let az = 0; az < 360; az += 3) {
    const { raDeg, decDeg } = altAzToRaDec(0, az, info.latitude, info.longitude, dateMs);
    flatPoints.push([raDeg, decDeg]);
  }
  strokeHorizonLoop(ctx, projectLoop(aladin, flatPoints, stats), '#f97316', maxSegmentPx, 1.5);

  regions.forEach((region) => {
    const points: [number, number][] = region.points.map((p) => {
      const { raDeg, decDeg } = altAzToRaDec(p.alt, p.az, info.latitude, info.longitude, dateMs);
      return [raDeg, decDeg];
    });
    strokeHorizonLoop(ctx, projectLoop(aladin, points, stats), '#dc2626', maxSegmentPx, 1);
  });
}

/** "YYYY-MM-DDTHH:mm" in local time, the string format <input type="datetime-local"> both
 * displays and expects back — new Date(dateString) parses that same format as local time too, so
 * this round-trips through the input without any UTC conversion drift. */
function toDatetimeLocalValue(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
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
  // All AstroBin footprints share one canvas (see drawAstrobinFootprints) instead of one
  // absolutely-positioned DOM element each — a couple hundred images' worth of transform/size
  // recalculation on every pan/zoom frame was the actual performance cost, and canvas drawing
  // touches no layout at all.
  const astrobinCanvasRef = useRef<HTMLCanvasElement>(null);
  const astrobinImagesRef = useRef<Map<string, HTMLImageElement>>(new Map());
  // Recomputed every redraw() call — consumed by the click handler below for hit testing, since a
  // canvas has no DOM nodes of its own to hang a click listener off.
  const astrobinHitRectsRef = useRef<AstrobinHitRect[]>([]);
  const astrobinFetchedRef = useRef(false);
  const appliedSurveyIdRef = useRef<string | null>(null);
  // The flat geometric horizon + any enabled artificial-horizon regions — plain canvas drawing
  // (drawHorizonOverlay), not Aladin's own A.polygon/graphicOverlay: Aladin's polygon renderer
  // crashes outright (TypeError reading 'x' of undefined) the moment any one vertex fails to
  // project onto the current view, which a 360°-sweep horizon loop does constantly unless the
  // whole sky happens to be in frame. world2pix() itself degrades gracefully (returns null); it's
  // only Aladin's *own* draw() that doesn't guard for that, so bypassing it avoids the crash.
  const horizonCanvasRef = useRef<HTMLCanvasElement>(null);
  // The Terrain panorama re-projection (drawTerrainOverlay) is plain canvas drawing, like the
  // AstroBin footprints, but on its own canvas layered underneath them (see index.css) rather than
  // sharing one — the two are cleared/redrawn independently and there's no reason to interleave
  // their draw calls.
  const terrainCanvasRef = useRef<HTMLCanvasElement>(null);
  const terrainImgRef = useRef<HTMLImageElement | null>(null);
  const terrainDebounceRef = useRef<number | undefined>(undefined);
  const horizonRetryRef = useRef<number | undefined>(undefined);
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
  // Horizon simulation: the flat 0°-altitude circle plus (if defined) the user's own artificial
  // horizon regions and Terrain panorama, all reprojected for whatever moment horizonTime is —
  // "now" by default (planning ahead needs a moment other than the current one). Not itself
  // persisted (a stale simulated time from a past session is more confusing to reload into than
  // starting fresh at "now" every time), unlike the showHorizon/showTerrain toggles.
  const [showHorizon, setShowHorizon] = useState(() => readStoredBoolean(SHOW_HORIZON_KEY));
  const [showTerrain, setShowTerrain] = useState(() => readStoredBoolean(SHOW_TERRAIN_KEY));
  const [horizonTime, setHorizonTime] = useState(() => Date.now());
  const [observatoryInfo, setObservatoryInfo] = useState<ObservatoryInfo | null>(null);
  const [artificialHorizon, setArtificialHorizon] = useState<ArtificialHorizonRegion[]>([]);
  const [terrainImageLoaded, setTerrainImageLoaded] = useState(false);
  const observatoryFetchedRef = useRef(false);
  // Wide-field shots otherwise sit permanently on top of any narrower-focal-length footprint of
  // the same area, since they're bigger and later in z-order — "hidden" collapses one down to
  // just its outline (plus a small reveal button) so whatever's underneath becomes clickable.
  // Not persisted: it's a per-session decluttering aid, not a setting worth remembering forever.
  const [hiddenAstrobinUrls, setHiddenAstrobinUrls] = useState<Set<string>>(new Set());
  // The footprint whose popover is currently open doubles as "selected" — see drawAstrobinFootprints
  // — so it's the only one z-ordering ever raises above the rest, replacing the old ephemeral hover
  // highlight with something that stays put until you actually close the popover. Position isn't
  // tracked here — see astrobinPopoverRef below — since it has to keep tracking the footprint's own
  // on-screen corner across pans/zooms, not just wherever it was when first opened.
  const [astrobinPopover, setAstrobinPopover] = useState<{
    footprint: AstrobinFootprint; date: string | null; loading: boolean; error: boolean;
  } | null>(null);
  const astrobinPopoverRef = useRef<HTMLDivElement>(null);
  // Aladin's own panning is a plain mousedown/mousemove/mouseup drag, not native HTML5 drag — the
  // browser still fires a normal 'click' on mouseup regardless of how far the mouse moved in
  // between, so a background drag-to-pan can't be told apart from a real click by event type
  // alone. Tracked at mousedown regardless of target (a drag can end outside the sky map entirely)
  // and compared against click position in both handleAstrobinClick and the outside-click effect.
  const astrobinMouseDownRef = useRef<{ x: number; y: number } | null>(null);
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

  // Recorded on every mousedown regardless of target — a pan can start inside the sky map and end
  // outside it (or vice versa) — so both listeners below can tell a background drag-to-pan apart
  // from an actual click, which a plain 'click' listener can't do on its own (see
  // astrobinMouseDownRef). Registered on the CAPTURE phase specifically: Aladin's own mousedown
  // handler on its canvas calls stopPropagation() during the bubble phase (confirmed — a bubble-
  // phase document listener never sees it at all), which capture doesn't run into since it fires
  // top-down before the event ever reaches that handler.
  useEffect(() => {
    function onMouseDown(e: MouseEvent) {
      astrobinMouseDownRef.current = { x: e.clientX, y: e.clientY };
    }
    document.addEventListener('mousedown', onMouseDown, true);
    return () => document.removeEventListener('mousedown', onMouseDown, true);
  }, []);

  // Closes only on a click OUTSIDE the whole sky map (not just outside the popover panel) —
  // clicks inside it, whether on a footprint, blank sky, or the popover's own buttons, are fully
  // handled by handleAstrobinClick below; splitting it this way (rather than one pointerdown
  // listener covering everything, like the sensor-settings popup above) avoids a same-click race
  // where clicking a second footprint to switch selection would immediately undo itself. Also
  // ignores drags (see astrobinMouseDownRef) so panning the map — which can end past the sky
  // map's own edge — doesn't deselect whatever's still tracking its footprint's corner (see
  // redraw()'s positioning block).
  useEffect(() => {
    if (!astrobinPopover) return undefined;
    function onClick(e: MouseEvent) {
      const down = astrobinMouseDownRef.current;
      if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > ASTROBIN_DRAG_CLICK_THRESHOLD_PX) return;
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setAstrobinPopover(null);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setAstrobinPopover(null);
    }
    document.addEventListener('click', onClick);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('click', onClick);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [astrobinPopover]);

  function openAstrobinPopover(f: AstrobinFootprint) {
    setAstrobinPopover({ footprint: f, date: null, loading: true, error: false });
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

  // The canvas itself is pointer-events:none (see .sky-map-astrobin-canvas — Aladin's own
  // dragging/zooming needs the mouse events underneath it), so clicks are handled here on the
  // container instead, via bubbling, and resolved against astrobinHitRectsRef by hand.
  function handleAstrobinClick(e: React.MouseEvent) {
    // Clicks on the popover's own buttons/links bubble here too (it's a child of this same
    // container) — let them handle themselves rather than reinterpreting as a deselect-on-miss.
    if (astrobinPopoverRef.current?.contains(e.target as Node)) return;
    // A background drag to pan the map still fires a 'click' on mouseup (Aladin's panning is a
    // plain mousedown/move/up drag, not native HTML5 drag, so the browser doesn't suppress it) —
    // ignore it rather than reinterpreting wherever the drag happened to end as a select/deselect.
    const down = astrobinMouseDownRef.current;
    if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > ASTROBIN_DRAG_CLICK_THRESHOLD_PX) return;
    const container = containerRef.current;
    if (!container) return;
    const rect = container.getBoundingClientRect();
    const hit = hitTestAstrobinFootprint(e.clientX - rect.left, e.clientY - rect.top, astrobinHitRectsRef.current);
    // A hit switches (or opens) the selection outright; a miss on blank sky deselects whatever
    // was selected — both resolved by a single authoritative call here rather than racing with
    // the outside-the-whole-map listener above, which only ever fires for clicks that don't reach
    // this handler at all.
    if (hit) openAstrobinPopover(hit.footprint);
    else setAstrobinPopover(null);
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

      const container = containerRef.current;
      const horizonCanvas = horizonCanvasRef.current;
      if (horizonCanvas && container) {
        const dpr = window.devicePixelRatio || 1;
        const targetW = Math.round(container.clientWidth * dpr);
        const targetH = Math.round(container.clientHeight * dpr);
        if (horizonCanvas.width !== targetW || horizonCanvas.height !== targetH) {
          horizonCanvas.width = targetW;
          horizonCanvas.height = targetH;
          horizonCanvas.style.width = `${container.clientWidth}px`;
          horizonCanvas.style.height = `${container.clientHeight}px`;
        }
        const hctx = horizonCanvas.getContext('2d');
        if (hctx) {
          hctx.setTransform(dpr, 0, 0, dpr, 0, 0);
          window.clearTimeout(horizonRetryRef.current);
          if (showHorizon && observatoryInfo && isValidLocation(observatoryInfo)) {
            const info = observatoryInfo;
            // Same transient-WebGL-exception hazard as the terrain overlay (see its own retry
            // comment above) can leave world2pix returning null for most/all of this loop's points
            // right after a zoom/pan brings fresh HiPS tiles in — without a retry, that one bad
            // frame's (near-)empty result just sits there until some unrelated redraw (e.g. a pan)
            // happens to land outside the bad window, which reads as "the horizon froze on zoom".
            const attempt = (retriesLeft: number) => {
              const stats: ProjectionStats = { exceptions: 0 };
              hctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
              drawHorizonOverlay(
                hctx, aladin, info, artificialHorizon, horizonTime,
                container.clientWidth, container.clientHeight, stats,
              );
              if (stats.exceptions > 0 && retriesLeft > 0) {
                horizonRetryRef.current = window.setTimeout(() => attempt(retriesLeft - 1), 200);
              }
            };
            attempt(3);
          } else {
            hctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
          }
        }
      }

      const canvas = astrobinCanvasRef.current;
      if (canvas && container) {
        // Backing store at devicePixelRatio for crisp rendering on retina displays; draw calls
        // below stay in the same CSS-pixel units world2pix() already returns (setTransform, not
        // scale, so this doesn't compound across repeated redraw() calls).
        const dpr = window.devicePixelRatio || 1;
        const targetW = Math.round(container.clientWidth * dpr);
        const targetH = Math.round(container.clientHeight * dpr);
        if (canvas.width !== targetW || canvas.height !== targetH) {
          canvas.width = targetW;
          canvas.height = targetH;
          canvas.style.width = `${container.clientWidth}px`;
          canvas.style.height = `${container.clientHeight}px`;
        }
        const ctx = canvas.getContext('2d');
        if (ctx) {
          ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
          ctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
          astrobinHitRectsRef.current = showAstrobin && astrobinFootprints
            ? drawAstrobinFootprints(
              ctx, aladin, astrobinFootprints, hiddenAstrobinUrls, astrobinPopover?.footprint.url ?? null,
              astrobinImagesRef.current, () => redrawRef.current(),
            )
            : [];
        }

        // The popover always sits at its footprint's own bottom-right screen corner, recomputed
        // every redraw so it keeps tracking that corner across pans/zooms instead of staying
        // wherever it was when first opened. Its own DOM element is positioned imperatively here
        // (rather than via React state) for the same reason every other footprint here is —
        // this needs to update every redraw, not trigger one.
        const popoverEl = astrobinPopoverRef.current;
        if (popoverEl) {
          const selectedRect = astrobinPopover
            && astrobinHitRectsRef.current.find((r) => r.footprint.url === astrobinPopover.footprint.url);
          if (selectedRect) {
            const [brx, bry] = screenRectBottomRight(selectedRect);
            popoverEl.style.display = 'block';
            popoverEl.style.left = `${clamp(brx, 0, container.clientWidth - ASTROBIN_POPOVER_WIDTH)}px`;
            popoverEl.style.top = `${clamp(bry, 0, container.clientHeight - ASTROBIN_POPOVER_HEIGHT_ESTIMATE)}px`;
          } else {
            // The selected footprint isn't currently projectable (panned off whatever part of the
            // sky it's on) — nothing sensible to anchor to until it is again.
            popoverEl.style.display = 'none';
          }
        }
      }

      const terrainCanvas = terrainCanvasRef.current;
      if (terrainCanvas && container) {
        const dpr = window.devicePixelRatio || 1;
        const targetW = Math.round(container.clientWidth * dpr);
        const targetH = Math.round(container.clientHeight * dpr);
        if (terrainCanvas.width !== targetW || terrainCanvas.height !== targetH) {
          terrainCanvas.width = targetW;
          terrainCanvas.height = targetH;
          terrainCanvas.style.width = `${container.clientWidth}px`;
          terrainCanvas.style.height = `${container.clientHeight}px`;
        }
        const tctx = terrainCanvas.getContext('2d');
        if (tctx) {
          tctx.setTransform(dpr, 0, 0, dpr, 0, 0);
          window.clearTimeout(terrainDebounceRef.current);
          if (showHorizon && showTerrain && terrainImageLoaded && terrainImgRef.current && observatoryInfo && isValidLocation(observatoryInfo)) {
            const img = terrainImgRef.current;
            const info = observatoryInfo;

            // Drawn every call (cheap, low-res) so the terrain visibly tracks the live gesture
            // instead of staying frozen at the pre-gesture framing until it settles — see
            // TERRAIN_LIVE_SAMPLE_COLS's own comment for the "sky map shrinks, ours doesn't" this
            // fixes. Its own exceptions are ignored: a blurry frame skipping one bad sample or two
            // isn't worth retrying when a sharper attempt is already scheduled below regardless.
            tctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
            drawTerrainOverlay(
              tctx, aladin, container.clientWidth, container.clientHeight, img, info, horizonTime,
              { exceptions: 0 }, TERRAIN_LIVE_SAMPLE_COLS,
            );

            // The high-res refinement stays debounced — walking TERRAIN_SAMPLE_COLS's much bigger
            // grid on every single animation-frame tick of a pan/zoom visibly bogged down the tab,
            // which the cheap live pass above doesn't. ~120ms after the gesture settles (same
            // window SessionTimeline's hover debounce uses) redraws it sharp.
            //
            // A zoom/pan that brings fresh HiPS tiles into view can leave Aladin's own WebGL
            // texture state transiently broken for a bit (see safePix2World's javadoc) — long
            // enough, sometimes, to still be broken when this fires. Retrying a few times instead
            // of accepting whatever this one attempt got means a zoom that lands in that window
            // doesn't leave the terrain layer stuck on the blurry live-pass version forever.
            const attempt = (retriesLeft: number) => {
              const stats: ProjectionStats = { exceptions: 0 };
              tctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
              drawTerrainOverlay(
                tctx, aladin, container.clientWidth, container.clientHeight, img, info, horizonTime,
                stats, TERRAIN_SAMPLE_COLS,
              );
              if (stats.exceptions > 0 && retriesLeft > 0) {
                terrainDebounceRef.current = window.setTimeout(() => attempt(retriesLeft - 1), 250);
              }
            };
            terrainDebounceRef.current = window.setTimeout(() => attempt(3), 120);
          } else {
            tctx.clearRect(0, 0, container.clientWidth, container.clientHeight);
          }
        }
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
    showAstrobin, astrobinFootprints, hiddenAstrobinUrls, astrobinPopover,
    planningFovEnabled, planningFovWidthArcmin, planningFovHeightArcmin, planningFovRotationDeg,
    showHorizon, showTerrain, horizonTime, observatoryInfo, artificialHorizon, terrainImageLoaded,
  ]);

  useEffect(() => {
    if (!ready) return;
    const aladin = aladinRef.current;
    const onChange = () => {
      redrawRef.current();
      saveCurrentView(aladin);
    };

    // Aladin's own 'positionChanged'/'zoomChanged' callbacks are throttled to 100ms internally
    // (B.CALLBACKS_THROTTLE_TIME_MS in aladin.js) — and 'zoomChanged' specifically never fires at
    // all during mouse-wheel zooming, since that animates the field of view frame-by-frame without
    // ever going through the internal updateZoomState() that triggers it. Polling both fov and
    // center RA/Dec every animation frame instead tracks pan/zoom at the browser's actual refresh
    // rate rather than Aladin's throttled one, and is cheap (a few number compares) next to the
    // WebGL redraw Aladin is already doing at the same rate.
    let lastFov = aladin.getFov()[0];
    let lastRaDec = aladin.getRaDec();
    let fovSettleTimer: number | undefined;

    // Aladin's own zoom-button handler can leave its rendered view visibly smaller than what
    // world2pix reports (confirmed via screenshot diffing: same FOV, same RA/Dec, sometimes a
    // full-canvas disc, sometimes a shrunken one with black margins on every side) — reachable at
    // any FOV via repeated real zoom-button clicks, not reproducible through setFov() alone.
    // Bisecting every axis by hand found it's specifically the view's declination sitting at 0°
    // (the celestial equator) at a wide FOV — landing back on dec exactly 0 breaks it again every
    // time, even after a 90° round trip; sitting a few degrees off 0 never breaks at all. But
    // rather than hard-code that one broken case, measure it directly: project two points a known
    // angular distance apart and compare the actual on-screen pixel gap to what that distance
    // should measure at the reported FOV. If Aladin's own render is desynced from what it reports,
    // this catches it regardless of which axis or FOV the next instance of this turns out to hinge
    // on, and skips the resync entirely on the (overwhelming majority of) frames where nothing is
    // actually wrong.
    function measuredGapPx(deltaDeg: number, stats?: ProjectionStats): number | null {
      const [ra, dec] = aladin.getRaDec();
      const a = safeWorld2Pix(aladin, ra, dec, stats);
      const b = safeWorld2Pix(aladin, ra, Math.max(-89, Math.min(89, dec + deltaDeg)), stats);
      if (!a || !b) return null;
      return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    const SCALE_CHECK_DELTA_DEG = 1;
    const SCALE_CHECK_MIN_RATIO = 0.5;
    const EQUATOR_DODGE_DEG = 3;
    const MAX_RESYNC_ATTEMPTS = 4;

    // A round-trip nudge (there and immediately back) is enough to force Aladin to recompute its
    // layout in the general case, but not for the dec-0 case above — that one has to end somewhere
    // else, hence attempt > 0 dodging by a growing amount instead of undoing itself. Re-measures
    // after giving the browser a couple of real animation frames (the "let our own rendering run
    // for one more frame" this needs — a redraw fired the instant after gotoRaDec() reads Aladin's
    // pre-update state) rather than assuming any single attempt worked. onDone always fires exactly
    // once, whether or not anything actually needed fixing, so callers can track completion.
    function attemptResync(attempt: number, onDone: () => void) {
      const container = containerRef.current;
      const fov = aladin.getFov()[0];
      const expectedPx = container ? (SCALE_CHECK_DELTA_DEG / fov) * container.clientHeight : null;
      const actualPx = measuredGapPx(SCALE_CHECK_DELTA_DEG);
      const looksBroken = expectedPx != null && (actualPx == null || actualPx < expectedPx * SCALE_CHECK_MIN_RATIO);

      if (!looksBroken || attempt > MAX_RESYNC_ATTEMPTS) {
        lastRaDec = aladin.getRaDec();
        onChange();
        onDone();
        return;
      }

      const [curRa, curDec] = aladin.getRaDec();
      if (attempt === 0) {
        aladin.gotoRaDec(curRa + 0.001, curDec);
        aladin.gotoRaDec(curRa, curDec);
      }
      else {
        const dodged = curDec + EQUATOR_DODGE_DEG * attempt;
        aladin.gotoRaDec(curRa, Math.max(-89, Math.min(89, dodged)));
      }
      requestAnimationFrame(() => requestAnimationFrame(() => attemptResync(attempt + 1, onDone)));
    }

    // Debounced so a burst of clicks only pays for one resync, ~150ms after the last of them (not
    // on every tick, to avoid fighting a live drag).
    const scheduleResync = () => {
      window.clearTimeout(fovSettleTimer);
      fovSettleTimer = window.setTimeout(() => attemptResync(0, () => {}), 150);
    };

    // Clicking zoom-out again once already at the FOV ceiling (or zoom-in already at the floor)
    // re-runs Aladin's own broken layout path without moving getFov()/getRaDec() at all — the poll
    // loop below never sees a value change and so never schedules the resync above on its own, the
    // exact case a real user hammering the zoom-out button at max zoom lands in. Listening for the
    // click directly (capture phase — Aladin's own handler doesn't stop it) covers that regardless
    // of whether anything the poller can observe actually moved.
    function onZoomButtonClick(e: MouseEvent) {
      if ((e.target as HTMLElement)?.closest?.('.aladin-zoom-in, .aladin-zoom-out')) {
        scheduleResync();
      }
    }
    containerRef.current?.addEventListener('click', onZoomButtonClick, true);

    // Backstop for every other way this can happen — real usage kept finding fresh ones (dragging
    // the timeline scrollbar, some sequence of clicks past the FOV ceiling, presumably others still
    // unknown) faster than each could be isolated and special-cased individually. Rather than chase
    // the next trigger, verify continuously: piggybacked on the poll loop below (own timer, not a
    // separate setInterval) so it shares its lifecycle exactly — same cleanup, and it goes idle
    // whenever rAF does (backgrounded tab), where a setInterval would keep firing regardless.
    // guardBusy skips overlapping runs (attemptResync's own retries already span multiple animation
    // frames) rather than piling up parallel gotoRaDec calls that would fight each other.
    let guardBusy = false;
    let lastGuardCheck = performance.now();
    const GUARD_INTERVAL_MS = 600;

    // Aladin's own 'positionChanged'/'zoomChanged' callbacks are throttled to 100ms internally
    // (B.CALLBACKS_THROTTLE_TIME_MS in aladin.js) — and 'zoomChanged' specifically never fires at
    // all during mouse-wheel zooming, since that animates the field of view frame-by-frame without
    // ever going through the internal updateZoomState() that triggers it. Polling both fov and
    // center RA/Dec every animation frame instead tracks pan/zoom at the browser's actual refresh
    // rate rather than Aladin's throttled one, and is cheap (a few number compares) next to the
    // WebGL redraw Aladin is already doing at the same rate.
    let frameId = requestAnimationFrame(function poll() {
      // getFov()/getRaDec() themselves — not just the pix2world/world2pix calls inside redraw()
      // — can transiently throw right after a zoom/pan brings fresh HiPS tiles into view (same
      // "Tex image ... lazy initialization" WebGL state as safePix2World's javadoc describes).
      // Both are called unconditionally, every frame, before reaching our own try/catch-guarded
      // code — so without this wrapper, that one throw skips the reschedule below and this whole
      // polling loop (pan, zoom, every overlay) goes dead for the rest of the session, exactly
      // matching "zooming out breaks all handling until you pan": a pan is just the next
      // interaction big enough that *something* else happens to notice the view changed, not
      // anything that actually revives this loop. try/finally guarantees the reschedule always
      // happens, so a bad frame here costs at most one skipped frame, never the whole loop.
      try {
        const fov = aladin.getFov()[0];
        const [ra, dec] = aladin.getRaDec();
        if (fov !== lastFov || ra !== lastRaDec[0] || dec !== lastRaDec[1]) {
          const fovChanged = fov !== lastFov;
          lastFov = fov;
          lastRaDec = [ra, dec];
          onChange();
          if (fovChanged) scheduleResync();
        }

        const now = performance.now();
        if (!guardBusy && now - lastGuardCheck > GUARD_INTERVAL_MS) {
          lastGuardCheck = now;
          guardBusy = true;
          attemptResync(0, () => { guardBusy = false; });
        }
      }
      catch {
        // Ignored — see comment above; next frame gets another chance.
      }
      finally {
        frameId = requestAnimationFrame(poll);
      }
    });
    return () => {
      cancelAnimationFrame(frameId);
      window.clearTimeout(fovSettleTimer);
      containerRef.current?.removeEventListener('click', onZoomButtonClick, true);
    };
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

  useEffect(() => {
    writeStoredBoolean(SHOW_HORIZON_KEY, showHorizon);
  }, [showHorizon]);

  useEffect(() => {
    writeStoredBoolean(SHOW_TERRAIN_KEY, showTerrain);
  }, [showTerrain]);

  // Fetched at most once, lazily on first enable — location/artificial-horizon only ever change if
  // the user reconfigures KStars itself, same reasoning as the NGC/Sh2 catalogs below.
  useEffect(() => {
    if (!showHorizon || observatoryFetchedRef.current) return;
    observatoryFetchedRef.current = true;
    fetchObservatoryInfo().then(setObservatoryInfo).catch(() => { /* no location configured — flat horizon/terrain just won't draw */ });
    fetchArtificialHorizon().then(setArtificialHorizon).catch(() => { /* no artificial horizon defined — flat horizon still draws */ });
  }, [showHorizon]);

  // The Terrain panorama is an 8+MB image — only fetched once "Terrain photo" is actually turned
  // on (not just because Horizon is), and only if KStars has one configured at all.
  useEffect(() => {
    if (!showHorizon || !showTerrain || !observatoryInfo?.hasTerrain || terrainImgRef.current) return;
    const img = new Image();
    img.onload = () => setTerrainImageLoaded(true);
    img.src = TERRAIN_IMAGE_URL;
    terrainImgRef.current = img;
  }, [showHorizon, showTerrain, observatoryInfo?.hasTerrain]);

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
      // Largest-FOV shots first (rendered first = sit at the bottom of the DOM stacking order) so a
      // wide-field footprint never sits on top of a narrower one of the same target by default —
      // hover (see the z-index rule in index.css) still lifts whichever one you're pointing at.
      .then((footprints) => [...footprints].sort((a, b) => footprintAreaDeg2(b) - footprintAreaDeg2(a)))
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
          <label className="sky-map-toggle">
            <input type="checkbox" checked={showHorizon} onChange={(e) => setShowHorizon(e.target.checked)} />
            Horizon
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
      {showHorizon && (
        <div className="sky-map-horizon">
          <label>
            Simulate at
            <input
              type="datetime-local"
              value={toDatetimeLocalValue(horizonTime)}
              onChange={(e) => {
                const t = new Date(e.target.value).getTime();
                if (!Number.isNaN(t)) setHorizonTime(t);
              }}
            />
          </label>
          <button type="button" onClick={() => setHorizonTime(Date.now())}>Now</button>
          {observatoryInfo?.hasTerrain && (
            <label className="sky-map-toggle">
              <input type="checkbox" checked={showTerrain} onChange={(e) => setShowTerrain(e.target.checked)} />
              Terrain photo
            </label>
          )}
          {observatoryInfo && !isValidLocation(observatoryInfo) && (
            <span className="sky-map-horizon-warning">No location configured in KStars</span>
          )}
          {artificialHorizon.length > 0 && (
            <span className="sky-map-horizon-note">
              + {artificialHorizon.length} artificial horizon region{artificialHorizon.length > 1 ? 's' : ''}
            </span>
          )}
        </div>
      )}
      <div
        ref={containerRef}
        className="sky-map"
        onClick={handleAstrobinClick}
      >
        {lastImageFilename && (
          <img
            ref={overlayImgRef}
            src={imageUrl(lastImageFilename, 600, lastImageStretch)}
            alt="Last capture"
            className="sky-map-last-image"
          />
        )}
        <canvas ref={terrainCanvasRef} className="sky-map-terrain-canvas" />
        <canvas ref={horizonCanvasRef} className="sky-map-horizon-canvas" />
        <canvas ref={astrobinCanvasRef} className="sky-map-astrobin-canvas" />
        {astrobinPopover && (
          <div className="sky-map-astrobin-popover" ref={astrobinPopoverRef}>
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
