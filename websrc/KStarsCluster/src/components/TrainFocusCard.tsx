import type { HfrSample } from '../api/types';
import { HfrChart } from './HfrChart';

interface Props {
  train: string;
  focusState?: string;
  focusRunning?: boolean;
  hfrHistory: HfrSample[];
}

export function TrainFocusCard({ train, focusState, focusRunning, hfrHistory }: Props) {
  const latestHfr = hfrHistory.length > 0 ? hfrHistory[hfrHistory.length - 1] : undefined;

  return (
    <div className="card card--wide">
      <h3>{train} · Focus</h3>
      <dl>
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
    </div>
  );
}
