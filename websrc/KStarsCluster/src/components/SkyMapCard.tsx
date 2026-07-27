import { useEffect, useRef, useState } from 'react';
import type { SchedulerJob } from '../api/types';
import { imageUrl, DEFAULT_STRETCH } from '../api/imageApi';

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
 * "nsns-sho" isn't a real simg.de survey — simg.de only publishes single-channel Hα/[OIII]/[SII]
 * HiPS and its own fixed-mapping combos (ohs8/hbr8/rgb8), no proper SHO/Hubble palette. Our own
 * backend (HipsProxyServlet, /hips/sho/*) fetches all three single-channel tiles for whatever
 * path Aladin requests and recombines them (R=SII, G=Hα, B=[OIII]) server-side, where CORS
 * doesn't apply — a client-side remap isn't possible since Aladin fetches tiles directly. */
const SURVEYS: SurveyOption[] = [
  { id: 'nsns-ohs8', label: 'NSNS [OIII]+Hα+[SII]', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/ohs8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-sho', label: 'NSNS SHO (Hubble palette)', custom: { url: '/hips/sho', frame: 'equatorial', order: 6 } },
  { id: 'dss2-color', label: 'DSS2 (color)', builtin: 'P/DSS2/color' },
  { id: 'nsns-rgb8', label: 'NSNS RGB continuum', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/rgb8', frame: 'equatorial', order: 5 } },
  { id: 'nsns-hbr8', label: 'NSNS Hα + continuum (color)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/hbr8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-halpha8', label: 'NSNS Hα (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/halpha8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-oiii8', label: 'NSNS [OIII] (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/oiii8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-sii8', label: 'NSNS [SII] (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/sii8', frame: 'equatorial', order: 6 } },
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

export function SkyMapCard({ mountCoords, activeJob, fov, pa, lastImageFilename }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const overlayImgRef = useRef<HTMLImageElement>(null);
  const aladinRef = useRef<any>(null);
  const mountCatalogRef = useRef<any>(null);
  const targetCatalogRef = useRef<any>(null);
  const fovOverlayRef = useRef<any>(null);
  const [ready, setReady] = useState(false);
  const [surveyId, setSurveyId] = useState(SURVEYS[0].id);
  const [showLastImage, setShowLastImage] = useState(false);
  const [followMount, setFollowMount] = useState(false);

  useEffect(() => {
    if (!window.A || !containerRef.current) return;
    window.A.init.then(() => {
      // Always init with a builtin survey — the default (possibly custom) survey from SURVEYS[0]
      // is applied right after via the surveyId effect below, which handles both cases.
      const aladin = window.A.aladin(containerRef.current, {
        survey: 'P/DSS2/color',
        fov: 60,
        target: '0 +0',
        cooFrame: 'equatorial',
        showFullscreenControl: false,
      });
      aladinRef.current = aladin;

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
    if (!ready || !aladinRef.current) return;
    const survey = SURVEYS.find((s) => s.id === surveyId) ?? SURVEYS[0];
    if (survey.builtin) {
      aladinRef.current.setImageSurvey(survey.builtin);
    } else if (survey.custom) {
      const hips = aladinRef.current.createImageSurvey(
        survey.id, survey.label, survey.custom.url, survey.custom.frame, survey.custom.order, { imgFormat: 'png' },
      );
      aladinRef.current.setImageSurvey(hips);
    }
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
  useEffect(() => {
    if (!ready) return;
    const aladin = aladinRef.current;
    const overlay = fovOverlayRef.current;

    function redraw() {
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
        const angle = (Math.atan2(px[1][1] - px[0][1], px[1][0] - px[0][0]) * 180) / Math.PI;

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
    }

    redraw();
    aladin.on('positionChanged', redraw);
    aladin.on('zoomChanged', redraw);
    return () => {
      // Aladin Lite v3 has no off(); harmless to leave stale listeners on an instance that's
      // being torn down along with its container.
    };
  }, [ready, mountCoords?.ra, mountCoords?.dec, fov?.widthArcmin, fov?.heightArcmin, pa, showLastImage, lastImageFilename]);

  // Keeps the view centered on the mount as it moves, instead of a one-shot "center now" click.
  useEffect(() => {
    if (!ready || !followMount || !mountCoords || !aladinRef.current) return;
    aladinRef.current.gotoRaDec(mountCoords.ra * 15, mountCoords.dec);
  }, [ready, followMount, mountCoords?.ra, mountCoords?.dec]);

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
            src={imageUrl(lastImageFilename, 600, DEFAULT_STRETCH)}
            alt="Last capture"
            className="sky-map-last-image"
          />
        )}
      </div>
    </div>
  );
}
