import { useStatusSocket } from './api/useStatusSocket';
import { getTrains, getDevices } from './api/types';
import { ConnectionCard } from './components/ConnectionCard';
import { TrainCard } from './components/TrainCard';
import { SchedulerCard } from './components/SchedulerCard';
import { CoolingCard } from './components/CoolingCard';
import { ObservatoryCard } from './components/ObservatoryCard';
import { CalibrationCard } from './components/CalibrationCard';
import { LogPanel } from './components/LogPanel';
import { DeviceList } from './components/DeviceList';

export function App() {
  const { status, connected } = useStatusSocket();

  const ready = status?.ekosReady === true;
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
        {ready && status && (
          <>
            <CoolingCard />
            <ObservatoryCard roofStatus={status.roofStatus} />
            <CalibrationCard />
            <SchedulerCard schedulerState={status.schedulerState} activeJob={status.activeJob} jobs={status.jobs} />
            {trains.map((train) => (
              <TrainCard
                key={train}
                train={train}
                captureStatus={status.captureStatus[train]}
                focusState={status.focusState[train]}
                captureRunning={status.captureRunning[train]}
                focusRunning={status.focusRunning[train]}
              />
            ))}
            <DeviceList devices={devices} />
          </>
        )}
      </div>

      <LogPanel />
    </div>
  );
}
