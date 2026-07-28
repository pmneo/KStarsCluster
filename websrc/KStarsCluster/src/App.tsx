import { useStatusSocket } from './api/useStatusSocket';
import { getTrains, getDevices, getLastImageFilename } from './api/types';
import { ConnectionCard } from './components/ConnectionCard';
import { TrainCard } from './components/TrainCard';
import { SchedulerCard } from './components/SchedulerCard';
import { CoolingCalibrationCard } from './components/CoolingCalibrationCard';
import { ObservatoryCard } from './components/ObservatoryCard';
import { LogPanel } from './components/LogPanel';
import { DeviceList } from './components/DeviceList';
import { SkyMapCard } from './components/SkyMapCard';
import { GuideCard } from './components/GuideCard';
import { AllskySection } from './components/AllskySection';

export function App() {
  const { status, connected } = useStatusSocket();

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
            <ObservatoryCard roofStatus={status.roofStatus} />
            {/* Scheduler and SkyMap are both card--wide (span 2) — placed back to back here, as
             * the first pair of wide cards after the normal-width ones above, so the grid's
             * left-to-right auto-flow lands them in the same row instead of pairing each with
             * whatever wide card happens to be next in the list. */}
            <SchedulerCard schedulerState={status.schedulerState} activeJob={status.activeJob} jobs={status.jobs} />
            <SkyMapCard
              mountCoords={status.mountCoords}
              activeJob={status.activeJob}
              fov={status.fov}
              pa={status.alignment?.pa}
              lastImageFilename={getLastImageFilename(status)}
            />
            {trains.map((train) => (
              <TrainCard
                key={train}
                train={train}
                captureStatus={status.captureStatus[train]}
                focusState={status.focusState[train]}
                captureRunning={status.captureRunning[train]}
                focusRunning={status.focusRunning[train]}
                hfrHistory={status.hfrHistory?.[train] ?? []}
                images={status.images?.[train] ?? []}
                sequenceQueue={status.sequenceQueue?.[train]}
              />
            ))}
            <GuideCard
              guideStatus={status.guideStatus}
              ditheringActive={status.ditheringActive}
              guideSigma={status.guideSigma}
              guideDeltaHistory={status.guideDeltaHistory ?? []}
            />
            <CoolingCalibrationCard />
            <DeviceList devices={devices} />
          </>
        )}
      </div>

      <LogPanel />
    </div>
  );
}
