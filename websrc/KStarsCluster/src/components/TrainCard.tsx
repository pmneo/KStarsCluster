import { useHfrHistory } from '../api/useHfrHistory';
import { actions } from '../api/actions';
import { HfrChart } from './HfrChart';
import { ImageStrip } from './ImageStrip';

interface Props {
  train: string;
  captureStatus?: string;
  focusState?: string;
  captureRunning?: boolean;
  focusRunning?: boolean;
}

export function TrainCard({ train, captureStatus, focusState, captureRunning, focusRunning }: Props) {
  const hfrHistory = useHfrHistory(train);
  const latestHfr = hfrHistory.length > 0 ? hfrHistory[hfrHistory.length - 1] : undefined;

  return (
    <div className="card card--wide">
      <h3>{train}</h3>
      <dl>
        <dt>Capture</dt>
        <dd>{captureStatus ?? '—'}{captureRunning ? ' (running)' : ''}</dd>
        <dt>Focus</dt>
        <dd>{focusState ?? '—'}{focusRunning ? ' (running)' : ''}</dd>
        {latestHfr && (
          <>
            <dt>Latest HFR</dt>
            <dd>{latestHfr.hfr.toFixed(2)} @ {latestHfr.position}</dd>
          </>
        )}
      </dl>
      <HfrChart samples={hfrHistory} />
      <div className="actions">
        <button onClick={() => actions.train.focusRun(train)} disabled={focusRunning}>Run Focus</button>
        <button onClick={() => actions.train.focusAbort(train)} disabled={!focusRunning}>Abort Focus</button>
        <button onClick={() => actions.train.captureAbort(train)} disabled={!captureRunning}>Abort Capture</button>
      </div>
      <ImageStrip train={train} />
    </div>
  );
}
