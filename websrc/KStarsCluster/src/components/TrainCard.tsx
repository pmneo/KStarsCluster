import type { HfrSample, CapturedImage, SequenceQueueStatus } from '../api/types';
import { actions } from '../api/actions';
import { HfrChart } from './HfrChart';
import { ImageStrip } from './ImageStrip';
import { CaptureQueue } from './CaptureQueue';

interface Props {
  train: string;
  captureStatus?: string;
  focusState?: string;
  captureRunning?: boolean;
  focusRunning?: boolean;
  hfrHistory: HfrSample[];
  images: CapturedImage[];
  sequenceQueue?: SequenceQueueStatus;
}

export function TrainCard({ train, captureStatus, focusState, captureRunning, focusRunning, hfrHistory, images, sequenceQueue }: Props) {
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
      <CaptureQueue queue={sequenceQueue} />
      <ImageStrip images={images} />
    </div>
  );
}
