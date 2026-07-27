import { useEffect, useRef, useState } from 'react';
import type { SchedulerJob } from '../api/types';

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

/** Custom entries verified against each survey's own HiPS `properties` file (frame/order/tile format). */
const SURVEYS: SurveyOption[] = [
  { id: 'dss2-color', label: 'DSS2 (color)', builtin: 'P/DSS2/color' },
  { id: 'nsns-rgb8', label: 'NSNS RGB continuum', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/rgb8', frame: 'equatorial', order: 5 } },
  { id: 'nsns-ohs8', label: 'NSNS [OIII]+Hα+[SII]', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/ohs8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-hbr8', label: 'NSNS Hα + continuum (color)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/hbr8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-halpha8', label: 'NSNS Hα (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/halpha8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-oiii8', label: 'NSNS [OIII] (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/oiii8', frame: 'equatorial', order: 6 } },
  { id: 'nsns-sii8', label: 'NSNS [SII] (8-bit)', custom: { url: 'https://www.simg.de/nebulae3/dr0_2/sii8', frame: 'equatorial', order: 6 } },
];

interface Props {
  mountCoords?: { ra: number; dec: number };
  activeJob: SchedulerJob | null;
}

export function SkyMapCard({ mountCoords, activeJob }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const aladinRef = useRef<any>(null);
  const mountCatalogRef = useRef<any>(null);
  const targetCatalogRef = useRef<any>(null);
  const [ready, setReady] = useState(false);
  const [surveyId, setSurveyId] = useState(SURVEYS[0].id);

  useEffect(() => {
    if (!window.A || !containerRef.current) return;
    window.A.init.then(() => {
      const aladin = window.A.aladin(containerRef.current, {
        survey: SURVEYS[0].builtin,
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

  function centerOnMount() {
    if (aladinRef.current && mountCoords) {
      aladinRef.current.gotoRaDec(mountCoords.ra * 15, mountCoords.dec);
    }
  }

  return (
    <div className="card card--wide">
      <h3>Sky Map</h3>
      <div className="sky-map-controls">
        <select value={surveyId} onChange={(e) => setSurveyId(e.target.value)}>
          {SURVEYS.map((s) => (
            <option key={s.id} value={s.id}>{s.label}</option>
          ))}
        </select>
        <button type="button" onClick={centerOnMount} disabled={!mountCoords}>Center on mount</button>
      </div>
      <div ref={containerRef} className="sky-map" />
    </div>
  );
}
