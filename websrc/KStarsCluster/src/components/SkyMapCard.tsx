import { useEffect, useRef, useState } from 'react';
import type { SchedulerJob } from '../api/types';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';

// Aladin Lite v3 is loaded via <script> in index.html, not bundled — it ships no official types.
declare global {
  interface Window {
    A: any;
  }
}

interface SurveyOption {
  id: string;
  label: string;
  builtin?: string;
  custom?: { url: string; frame: string; order: number };
}

/** Custom entries verified against each survey's own HiPS `properties` file (frame/order/tile format).
 * "nsns-sho"/"nsns-hso" aren't real simg.de surveys — simg.de only publishes single-channel
 * Hα/[OIII]/[SII] HiPS and its own fixed-mapping combos (ohs8/hbr8/rgb8), no proper Hubble-style
 * palette. Our own backend (HipsProxyServlet, /hips/{sho,hso}/*) fetches all three single-channel
 * tiles for whatever path Aladin requests and recombines them server-side, where CORS doesn't
 * apply — a client-side remap isn't possible since Aladin fetches tiles directly.
 * Every other NSNS entry also goes through our backend (/hips/{survey}/*, straight passthrough)
 * rather than simg.de directly — same HipsProxyServlet, just caching each tile instead of
 * recombining it, so repeat pans/zooms don't keep re-hitting simg.de's server.
 * SHO is listed first — it's the default survey (see surveyId's initial state below). */
const SURVEYS: SurveyOption[] = [
  { id: 'nsns-sho', label: 'NSNS SHO (Hubble palette)', custom: { url: '/hips/sho', frame: 'equatorial', order: 6 } },
  { id: 'nsns-hso', label: 'NSNS HSO (Hα/[SII]/[OIII])', custom: { url: '/hips/hso', frame: 'equatorial', order: 6 } },
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
const VIEW_KEY = 'skymap.view';
const DEFAULT_FOV_DEG = 10;

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
  const containerRef = useRef<HTMLDivElement>(null);
  const overlayImgRef = useRef<HTMLImageElement>(null);
  const aladinRef = useRef<any>(null);
  const mountCatalogRef = useRef<any>(null);
  const targetCatalogRef = useRef<any>(null);
  const fovOverlayRef = useRef<any>(null);
  const appliedSurveyIdRef = useRef<string | null>(null);
  const [ready, setReady] = useState(false);
  const [surveyId, setSurveyId] = useState(SURVEYS[0].id);
  // Persisted across reloads (see FOLLOW_MOUNT_KEY/SHOW_LAST_IMAGE_KEY) — both are "set once,
  // forget about it" toggles, so a reload silently reverting them is more surprising than useful.
  const [showLastImage, setShowLastImage] = useState(() => readStoredBoolean(SHOW_LAST_IMAGE_KEY));
  const [followMount, setFollowMount] = useState(() => readStoredBoolean(FOLLOW_MOUNT_KEY));
  const [lastImageStretch, setLastImageStretch] = useState<StretchSettings>(DEFAULT_STRETCH);

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
  }, [mountCoords?.ra, mountCoords?.dec, fov?.widthArcmin, fov?.heightArcmin, pa, showLastImage, lastImageFilename]);

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
    <div className="card card--wide">
      <h3>Sky Map</h3>
      <div className="sky-map-controls">
        <select value={surveyId} onChange={(e) => setSurveyId(e.target.value)}>
          {SURVEYS.map((s) => (
            <option key={s.id} value={s.id}>{s.label}</option>
          ))}
        </select>
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
        </div>
      </div>
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
