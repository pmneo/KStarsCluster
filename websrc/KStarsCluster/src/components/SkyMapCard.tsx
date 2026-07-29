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

const FOLLOW_MOUNT_KEY = 'skymap.followMount';
const SHOW_LAST_IMAGE_KEY = 'skymap.showLastImage';
const SHOW_NGC_KEY = 'skymap.showNgc';
const SHOW_SH2_KEY = 'skymap.showSh2';
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
  const appliedSurveyIdRef = useRef<string | null>(null);
  const [ready, setReady] = useState(false);
  const [surveyId, setSurveyId] = useState(SURVEYS[0].id);
  // Persisted across reloads (see FOLLOW_MOUNT_KEY/SHOW_LAST_IMAGE_KEY) — both are "set once,
  // forget about it" toggles, so a reload silently reverting them is more surprising than useful.
  const [showLastImage, setShowLastImage] = useState(() => readStoredBoolean(SHOW_LAST_IMAGE_KEY));
  const [followMount, setFollowMount] = useState(() => readStoredBoolean(FOLLOW_MOUNT_KEY));
  const [showNgc, setShowNgc] = useState(() => readStoredBoolean(SHOW_NGC_KEY));
  const [showSh2, setShowSh2] = useState(() => readStoredBoolean(SHOW_SH2_KEY));
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

      overlay.removeAll();
      if (!mountCoords || !fov) {
        if (overlayImgRef.current) overlayImgRef.current.style.display = 'none';
        return;
      }

      const raDeg = mountCoords.ra * 15;
      const corners = fovCorners(raDeg, mountCoords.dec, fov.widthArcmin / 60, fov.heightArcmin / 60, pa ?? 0);
      overlay.add(window.A.polygon(corners));

      if (overlayImgRef.current && showLastImage && lastImageFilename) {
        const px = corners.map(([ra, dec]) => aladin.world2pix(ra, dec));
        const cx = (px[0][0] + px[2][0]) / 2;
        const cy = (px[0][1] + px[2][1]) / 2;
        const topW = Math.hypot(px[1][0] - px[0][0], px[1][1] - px[0][1]);
        const rightH = Math.hypot(px[2][0] - px[1][0], px[2][1] - px[1][1]);
        // +180: the FOV rectangle itself is 180°-symmetric so this never showed up there, but
        // the actual photo is not — verified empirically that it renders upside down without
        // this correction.
        const angle = (Math.atan2(px[1][1] - px[0][1], px[1][0] - px[0][0]) * 180) / Math.PI + 180;

        const img = overlayImgRef.current;
        img.style.display = 'block';
        img.style.width = `${topW}px`;
        img.style.height = `${rightH}px`;
        img.style.left = `${cx}px`;
        img.style.top = `${cy}px`;
        img.style.marginLeft = `${-topW / 2}px`;
        img.style.marginTop = `${-rightH / 2}px`;
        img.style.transform = `rotate(${angle}deg)`;
      } else if (overlayImgRef.current) {
        overlayImgRef.current.style.display = 'none';
      }
    };

    redrawRef.current();
  }, [
    mountCoords?.ra, mountCoords?.dec, fov?.widthArcmin, fov?.heightArcmin, pa, showLastImage, lastImageFilename,
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
      </div>
    </div>
  );
}
