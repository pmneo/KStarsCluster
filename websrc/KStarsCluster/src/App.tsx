import { useEffect, useState } from 'react';
import { useStatusSocket } from './api/useStatusSocket';
import { getTrains, getDevices, getLastImageFilename, type ViewerImage, type TimelineCaptureSelection } from './api/types';
import { fetchAllskyCameras, fetchAllskyChart, nearestAllskyMatches, type AllskyCameraInfo, type AllskyMatch, type AllskyPoint } from './api/allskyApi';
import { ConnectionCard } from './components/ConnectionCard';
import { TrainCaptureCard } from './components/TrainCaptureCard';
import { TrainFocusCard } from './components/TrainFocusCard';
import { SchedulerCard } from './components/SchedulerCard';
import { CoolingCalibrationCard } from './components/CoolingCalibrationCard';
import { ObservatoryCard } from './components/ObservatoryCard';
import { CurrentStatusCard } from './components/CurrentStatusCard';
import { LogPanel } from './components/LogPanel';
import { DeviceList } from './components/DeviceList';
import { SkyMapCard } from './components/SkyMapCard';
import { GuideCard } from './components/GuideCard';
import { AllskySection } from './components/AllskySection';
import { SessionTimeline } from './components/SessionTimeline';
import { ImageViewer } from './components/ImageViewer';

// A capture can be from anywhere in tonight's session, not just the last few hours — rather than
// keeping a standing wide-window poll running at all times just in case something gets clicked
// (which was the first cut of this feature — needlessly heavy on indi-allsky's own loop endpoint,
// see the "less data from the loop" ask that replaced it), this window is fetched on demand,
// centered on whichever capture is actually selected (see the effect below and
// AllskyClient.fetchLoop's javadoc for why "centered" needs the timestamp pushed forward by half
// this). 3600s total (±30min around the capture) comfortably covers indi-allsky's own ~60s-per-
// frame cadence even if that particular stretch of the night had a gap.
const ALLSKY_COMPARE_WINDOW_S = 3600;

export function App() {
  const { status, connected } = useStatusSocket();
  // Lifted to App instead of living in ImageStrip/SessionTimeline — it's a single full-screen
  // overlay regardless of which card's image opened it, so it only makes sense to render once.
  const [viewerImage, setViewerImage] = useState<ViewerImage | null>(null);

  // Transient (hover) and persistent (click) Session Timeline capture selection — see
  // TimelineCaptureSelection and ImageStrip's `override`. Also lifted to App: the timeline and the
  // ImageStrip it targets are siblings (inside different TrainCards), so this is the shared
  // ancestor, same reasoning as viewerImage above.
  const [hoveredCapture, setHoveredCapture] = useState<TimelineCaptureSelection | null>(null);
  const [pinnedCapture, setPinnedCapture] = useState<TimelineCaptureSelection | null>(null);
  const activeCapture = hoveredCapture ?? pinnedCapture;

  const [allskyCameras, setAllskyCameras] = useState<Record<string, AllskyCameraInfo>>({});
  const [activeAllskyMatches, setActiveAllskyMatches] = useState<AllskyMatch[]>([]);

  useEffect(() => {
    fetchAllskyCameras().then(setAllskyCameras).catch(() => {});
  }, []);

  // Only fetched when there's actually something selected to compare against, and only the one
  // window that covers it — see ALLSKY_COMPARE_WINDOW_S above.
  useEffect(() => {
    if (!activeCapture) {
      setActiveAllskyMatches([]);
      return undefined;
    }
    const cams = Object.keys(allskyCameras);
    if (cams.length === 0) return undefined;

    let cancelled = false;
    // Pushed forward by half the window so the returned [anchor-limitS, anchor] range is centered
    // on the capture instead of ending at it — see AllskyClient.fetchLoop's javadoc.
    const anchorMs = activeCapture.ts + (ALLSKY_COMPARE_WINDOW_S / 2) * 1000;

    Promise.all(cams.map((cam) => (
      fetchAllskyChart(cam, ALLSKY_COMPARE_WINDOW_S, anchorMs)
        .then((points): [string, AllskyPoint[]] => [cam, points])
        .catch((): [string, AllskyPoint[]] => [cam, []])
    ))).then((results) => {
      if (cancelled) return;
      const history: Record<string, AllskyPoint[]> = {};
      results.forEach(([cam, points]) => { history[cam] = points; });
      setActiveAllskyMatches(nearestAllskyMatches(activeCapture.ts, history, allskyCameras));
    });

    return () => { cancelled = true; };
  }, [activeCapture, allskyCameras]);

  const trains = status ? getTrains(status) : [];
  const devices = status ? getDevices(status) : [];

  return (
    <div className="app">
      <header>
        <h1>KStarsCluster</h1>
        <span className={connected ? 'connection connected' : 'connection disconnected'}>
          {connected ? 'live' : 'reconnecting…'}
        </span>
      </header>

      <div className="grid">
        {status && (
          <ConnectionCard
            kstarsRunning={status.kstarsRunning}
            ekosReady={status.ekosReady}
            ekosStatus={status.ekosStatus}
            manualStartRequested={status.manualStartRequested}
            automationSuspended={status.automationSuspended}
          />
        )}
        <AllskySection />
        {status && (
          <>
            <CurrentStatusCard
              captureRunning={status.captureRunning}
              focusRunning={status.focusRunning}
              mountStatus={status.mountStatus}
              alignStatus={status.alignStatus}
              gudingRunning={status.gudingRunning}
              ditheringActive={status.ditheringActive}
              sequenceQueue={status.sequenceQueue ?? {}}
              guideDeltaHistory={status.guideDeltaHistory ?? []}
              guideSigma={status.guideSigma}
            />
            <SessionTimeline
              images={status.images ?? {}}
              hfrHistory={status.hfrHistory ?? {}}
              guideDeltaHistory={status.guideDeltaHistory ?? []}
              timelineEvents={status.timelineEvents ?? []}
              onHoverCapture={setHoveredCapture}
              onSelectCapture={setPinnedCapture}
              activeCapture={activeCapture}
              activeAllskyMatches={activeAllskyMatches}
              onClearActiveCapture={() => setPinnedCapture(null)}
              onOpenImage={setViewerImage}
            />
            {/* Scheduler and SkyMap are both card--wide (span 2) — placed back to back here, as
             * the first pair of wide cards after the normal-width ones above, so the grid's
             * left-to-right auto-flow lands them in the same row instead of pairing each with
             * whatever wide card happens to be next in the list. */}
            <SchedulerCard schedulerState={status.schedulerState} activeJob={status.activeJob} jobs={status.jobs} />
            <SkyMapCard
              mountCoords={status.mountCoords}
              activeJob={status.activeJob}
              jobs={status.jobs}
              ekosReady={status.ekosReady}
              fov={status.fov}
              pa={status.alignment?.pa}
              lastImageFilename={getLastImageFilename(status)}
            />
            {trains.map((train) => (
              <TrainCaptureCard
                key={`${train}-capture`}
                train={train}
                captureStatus={status.captureStatus[train]}
                captureRunning={status.captureRunning[train]}
                images={status.images?.[train] ?? []}
                sequenceQueue={status.sequenceQueue?.[train]}
                onOpenImage={setViewerImage}
              />
            ))}
            {trains.map((train) => (
              <TrainFocusCard
                key={`${train}-focus`}
                train={train}
                focusState={status.focusState[train]}
                focusRunning={status.focusRunning[train]}
                hfrHistory={status.hfrHistory?.[train] ?? []}
              />
            ))}
            <GuideCard
              guideStatus={status.guideStatus}
              ditheringActive={status.ditheringActive}
              guideSigma={status.guideSigma}
              guideDeltaHistory={status.guideDeltaHistory ?? []}
            />
            <ObservatoryCard roofStatus={status.roofStatus} />
            <CoolingCalibrationCard />
            <DeviceList devices={devices} />
          </>
        )}
      </div>

      <LogPanel />
      <ImageViewer image={viewerImage} onClose={() => setViewerImage(null)} />
    </div>
  );
}
