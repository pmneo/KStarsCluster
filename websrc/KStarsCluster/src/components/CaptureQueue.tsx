import { parseLocaleNumber, type SequenceQueueStatus } from '../api/types';

function formatDuration(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds) || totalSeconds < 0) return '—';
  const s = Math.round(totalSeconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${m}m ${sec}s`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

export function CaptureQueue({ queue }: { queue?: SequenceQueueStatus }) {
  if (!queue || queue.sequence.length === 0) {
    return null;
  }

  const hasActiveJob = queue.activeJobID >= 0;

  return (
    <div className="capture-queue">
      <div className="capture-queue-progress">
        <div className="progress-bar">
          <div className="progress-bar-fill" style={{ width: `${Math.min(100, Math.max(0, queue.progressPercentage))}%` }} />
        </div>
        <span>
          {queue.progressPercentage.toFixed(0)}% · {queue.pendingJobCount} pending of {queue.jobCount} ·
          remaining {formatDuration(queue.overallRemainingTime)}
        </span>
      </div>

      {hasActiveJob && (
        <dl>
          <dt>Active step</dt>
          <dd>
            {queue.activeJobFilterName || '—'} {queue.activeJobState && <>({queue.activeJobState})</>}
          </dd>
          <dt>Exposure</dt>
          <dd>
            {queue.activeJobExposureProgress.toFixed(1)}s / {queue.activeJobExposureDuration.toFixed(1)}s
          </dd>
          <dt>Image</dt>
          <dd>{queue.activeJobImageProgress} / {queue.activeJobImageCount}</dd>
          <dt>Step remaining</dt>
          <dd>{formatDuration(queue.activeJobRemainingTime)}</dd>
        </dl>
      )}

      <div className="table-scroll">
        <table className="sequence-steps">
          <thead>
            <tr>
              <th>Type</th>
              <th>Filter</th>
              <th>Count</th>
              <th>Exp</th>
              <th>Bin</th>
              <th>Temp</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {queue.sequence.map((step, idx) => {
              const isActive = hasActiveJob && (idx === queue.activeJobID || idx + 1 === queue.activeJobID);
              return (
                <tr key={idx} className={isActive ? 'active-job' : ''}>
                  <td>{step.Type}</td>
                  <td>{step.Filter}</td>
                  <td>{step.Count}</td>
                  <td>{parseLocaleNumber(step.Exp).toFixed(1)}s</td>
                  <td>{step.Bin}</td>
                  <td>{step.EnforceTemperature ? `${step.Temperature}°C` : '—'}</td>
                  <td>{step.Status}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
