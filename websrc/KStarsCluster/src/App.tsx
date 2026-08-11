import { useEffect, useRef, useState } from 'react';
import { useStatusSocket } from './api/useStatusSocket';
import { getTrains, getDevices, getLastImageFilename, type StatusSnapshot, type ViewerImage, type TimelineCaptureSelection } from './api/types';
import { fetchAllskyCameras, fetchAllskyChart, fetchAllskyKeograms, nearestAllskyMatches, allskyImageUrl, type AllskyCameraInfo, type AllskyMatch, type AllskyNightKeogram, type AllskyPoint } from './api/allskyApi';
import { ConnectionCard } from './components/ConnectionCard';
import { TrainCaptureCard } from './components/TrainCaptureCard';
import { TrainFocusCard } from './components/TrainFocusCard';
import { SchedulerCard } from './components/SchedulerCard';
import { CoolingCalibrationCard } from './components/CoolingCalibrationCard';
import { ObservatoryCard } from './components/ObservatoryCard';
import { CurrentStatusCard } from './components/CurrentStatusCard';
import { LogPanel } from './components/LogPanel';
import { DeviceList } from './components/DeviceList';
import { SkyMapCard } from 'skymap-widget';
import { liveSkyMapDataSource } from './skymap/liveDataSource';
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

// The outdoor camera whose star count is worth plotting against the session — "obsy" points at
// the dome interior (showDetails: false server-side), never meaningful here.
const ALLSKY_TIMELINE_CAM = 'default';
// A SINGLE request sized to an entire night's duration still gets truncated by indi-allsky's own
// entry-count cap (confirmed empirically: anchoring limitS exactly to a night's length still only
// returns the last ~40% of it) — covering a night fully means walking backward across it in
// several smaller anchored sub-windows instead, each comfortably under that cap.
const ALLSKY_NIGHT_SUBWINDOW_S = 4 * 3600;
// Small overlap so two adjacent sub-windows' entry-count cutoffs don't leave a sliver gap right at
// the boundary between them.
const ALLSKY_SUBWINDOW_PAD_S = 300;
// How far back to sub-window for whatever's still accumulating right now (a currently-active night
// has no keogram yet, so it can't be looked up by bounds the way completed ones are) — generous
// enough for a full night-in-progress without walking back into the previous day for no reason.
const ALLSKY_LIVE_LOOKBACK_S = 12 * 3600;
const ALLSKY_STAR_HISTORY_POLL_MS = 5 * 60_000;
// A completed night's keogram only changes once a day — matches the backend's own cache TTL
// (AllskyClient.NIGHT_KEOGRAM_CACHE_MS), no point polling faster than that.
const NIGHT_KEOGRAM_POLL_MS = 15 * 60_000;

/** Earliest timestamp anywhere in the retained session history — closely mirrors (without fully
 * duplicating) SessionTimeline's own fullStart calculation, just enough to know which of the
 * already-fetched nightKeograms are actually relevant to fetch dense star history for (see the
 * star-history effect below). */
function computeSessionStartMs(status: StatusSnapshot): number | undefined {
  const allTs: number[] = [];
  for (const imgs of Object.values(status.images ?? {})) {
    for (const img of imgs) allTs.push(img.ts - img.exposure * 1000);
  }
  for (const samples of Object.values(status.hfrHistory ?? {})) {
    for (const s of samples) allTs.push(s.ts);
  }
  for (const e of status.timelineEvents ?? []) allTs.push(e.ts);
  for (const s of status.guideDeltaHistory ?? []) allTs.push(s.ts);
  return allTs.length > 0 ? Math.min(...allTs) : undefined;
}

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
  // Standing (not on-demand, unlike activeAllskyMatches above) — the Session Timeline's "Allsky
  // Stars" lane needs the whole visible window's history, not just one moment's nearest match.
  const [allskyStarHistory, setAllskyStarHistory] = useState<AllskyPoint[]>([]);
  const [nightKeograms, setNightKeograms] = useState<AllskyNightKeogram[]>([]);

  // Read inside the polling effects below via a ref rather than a `[status]` dependency — status
  // itself updates far more often (live per-second broadcast) than these polls should ever fire.
  const statusRef = useRef(status);
  useEffect(() => { statusRef.current = status; }, [status]);

  useEffect(() => {
    fetchAllskyCameras().then(setAllskyCameras).catch(() => {});
  }, []);

  // Anchored per-night (using the dusk/dawn bounds nightKeograms already computed) rather than
  // fixed-size chunks stacked back from "now" — indi-allsky's own loop endpoint caps how many
  // entries it returns per request regardless of the requested window size (confirmed empirically:
  // even a single night's frames alone sit right at that cap), so a plain "last 86400s" query
  // mostly just returns the last few daytime hours and never actually reaches into a night at all.
  // Depends on nightKeograms (updates every 15min, see that poll below) rather than a ref, since
  // re-running this effect whenever the night list changes is exactly when it needs to reconsider
  // which nights are worth fetching dense history for.
  useEffect(() => {
    let cancelled = false;

    // Walks backward from `endMs` in ALLSKY_NIGHT_SUBWINDOW_S-sized bites until `totalS` seconds
    // are covered — one indi-allsky "loop" query per bite, each small enough to stay under its
    // entry-count cap regardless of how many are needed to cover the whole stretch.
    function subWindowRequests(endMs: number, totalS: number) {
      const subCount = Math.max(1, Math.ceil(totalS / ALLSKY_NIGHT_SUBWINDOW_S));
      return Array.from({ length: subCount }, (_, i) => (
        fetchAllskyChart(ALLSKY_TIMELINE_CAM, ALLSKY_NIGHT_SUBWINDOW_S + ALLSKY_SUBWINDOW_PAD_S, endMs - i * ALLSKY_NIGHT_SUBWINDOW_S * 1000)
          .catch((): AllskyPoint[] => [])
      ));
    }

    function poll() {
      const currentStatus = statusRef.current;
      const sessionStartMs = currentStatus ? computeSessionStartMs(currentStatus) : undefined;
      const relevantNights = sessionStartMs
        ? nightKeograms.filter((k) => k.endMs !== undefined && k.endMs >= sessionStartMs)
        : [];

      const requests = relevantNights.flatMap((k) => (
        subWindowRequests(k.endMs as number, Math.ceil(((k.endMs as number) - (k.startMs as number)) / 1000))
      ));
      // Plus whatever's still accumulating right now — an in-progress night has no keogram yet,
      // so it can't be looked up by bounds the way the completed ones above are.
      requests.push(...subWindowRequests(Date.now(), ALLSKY_LIVE_LOOKBACK_S));

      Promise.all(requests).then((chunks) => {
        if (cancelled) return;
        // Merge into the PREVIOUS state rather than replacing it outright. This effect can end up
        // re-running in overlapping bursts (nightKeograms updating more than once in quick
        // succession as its own poll settles) — an earlier run's still-in-flight fetch covering
        // fewer nights (e.g. only the live fallback, before nightKeograms had populated yet) can
        // resolve AFTER a later, more complete run, and a plain replace would then wipe out
        // already-correct older nights' data with that skinnier result. Merging is safe because
        // completed nights' data never changes once fetched — only additive updates happen here.
        setAllskyStarHistory((prev) => {
          const merged = new Map<number, AllskyPoint>();
          prev.forEach((p) => merged.set(p.ts, p));
          chunks.flat().forEach((p) => merged.set(p.ts, p));
          return Array.from(merged.values()).sort((a, b) => a.ts - b.ts);
        });
      });
    }
    poll();
    const interval = window.setInterval(poll, ALLSKY_STAR_HISTORY_POLL_MS);
    return () => { cancelled = true; window.clearInterval(interval); };
  }, [nightKeograms]);

  useEffect(() => {
    let cancelled = false;
    function poll() {
      fetchAllskyKeograms(ALLSKY_TIMELINE_CAM)
        .then((keograms) => { if (!cancelled) setNightKeograms(keograms); })
        .catch(() => {});
    }
    poll();
    const interval = window.setInterval(poll, NIGHT_KEOGRAM_POLL_MS);
    return () => { cancelled = true; window.clearInterval(interval); };
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

  // Resolved once here (image URL built, missing twilight bounds filtered out) so SessionTimeline
  // stays free of allsky-specific cam-name/path knowledge — it only ever sees ready-to-render data,
  // same as how it already only receives resolved AllskyMatch[] rather than raw camera config.
  const resolvedNightKeograms = nightKeograms
    .filter((k) => k.startMs !== undefined && k.endMs !== undefined)
    .map((k) => ({
      label: k.dayDateLong,
      startMs: k.startMs as number,
      endMs: k.endMs as number,
      imageUrl: allskyImageUrl(ALLSKY_TIMELINE_CAM, k.path),
    }));

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
              allskyHistory={allskyStarHistory}
              nightKeograms={resolvedNightKeograms}
              onClearActiveCapture={() => setPinnedCapture(null)}
              onOpenImage={setViewerImage}
            />
            {/* Scheduler and SkyMap are both card--wide (span 2) — placed back to back here, as
             * the first pair of wide cards after the normal-width ones above, so the grid's
             * left-to-right auto-flow lands them in the same row instead of pairing each with
             * whatever wide card happens to be next in the list. */}
            <SchedulerCard schedulerState={status.schedulerState} activeJob={status.activeJob} jobs={status.jobs} />
            <SkyMapCard
              dataSource={liveSkyMapDataSource}
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
