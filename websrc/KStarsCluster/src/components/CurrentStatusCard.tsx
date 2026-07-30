import type { GuideDeltaSample, SequenceQueueStatus } from '../api/types';
import { formatDuration } from './CaptureQueue';

interface Props {
  captureRunning: Record<string, boolean>;
  focusRunning: Record<string, boolean>;
  mountStatus: string;
  alignStatus: string;
  gudingRunning: boolean;
  ditheringActive: boolean;
  sequenceQueue: Record<string, SequenceQueueStatus>;
  guideDeltaHistory: GuideDeltaSample[];
  guideSigma?: { ra: number; de: number };
}

const ALIGN_ACTIVE = new Set(['ALIGN_PROGRESS', 'ALIGN_SLEWING', 'ALIGN_SYNCING', 'ALIGN_ROTATING']);
const MOUNT_SLEWING = new Set(['MOUNT_SLEWING', 'MOUNT_MOVING']);

type ActivityKind = 'aligning' | 'focusing' | 'slewing' | 'capturing' | 'guiding' | 'idle';

/** What's actually happening right now, most disruptive-to-imaging first: aligning/focusing/
 * slewing all pause capture, so they're worth calling out ahead of "Capturing" even though
 * capture is the more common headline state. Guiding runs continuously alongside capture, so it
 * only becomes the headline when nothing else is going on (e.g. guiding during a calibration
 * pause). */
function currentActivity(active: Record<ActivityKind, boolean>): ActivityKind {
  const order: ActivityKind[] = ['aligning', 'focusing', 'slewing', 'capturing', 'guiding'];
  return order.find((kind) => active[kind]) ?? 'idle';
}

const ACTIVITY_LABELS: Record<ActivityKind, string> = {
  aligning: 'Aligning',
  focusing: 'Focusing',
  slewing: 'Slewing',
  capturing: 'Capturing',
  guiding: 'Guiding',
  idle: 'Idle',
};

export function CurrentStatusCard({
  captureRunning, focusRunning, mountStatus, alignStatus, gudingRunning, ditheringActive,
  sequenceQueue, guideDeltaHistory, guideSigma,
}: Props) {
  const active: Record<ActivityKind, boolean> = {
    aligning: ALIGN_ACTIVE.has(alignStatus),
    focusing: Object.values(focusRunning).some(Boolean),
    slewing: MOUNT_SLEWING.has(mountStatus),
    capturing: Object.values(captureRunning).some(Boolean),
    guiding: gudingRunning,
    idle: false,
  };
  const activity = currentActivity(active);

  const runningCaptures = Object.entries(sequenceQueue)
    .filter(([train]) => captureRunning[train])
    .map(([train, queue]) => ({ train, queue }));

  const latestGuideDelta = guideDeltaHistory.length > 0 ? guideDeltaHistory[guideDeltaHistory.length - 1] : undefined;

  return (
    <div className="card">
      <h3>Current</h3>
      <div className={`current-status current-status--${activity}`}>{ACTIVITY_LABELS[activity]}</div>

      {runningCaptures.length > 0 ? (
        <div className="current-captures">
          {runningCaptures.map(({ train, queue }) => (
            <div className="current-capture-row" key={train}>
              <span className="current-capture-label">
                {train} · {queue.activeJobFilterName || '—'} {queue.activeJobExposureProgress.toFixed(0)}/{queue.activeJobExposureDuration.toFixed(0)}s
              </span>
              <div className="progress-bar">
                <div className="progress-bar-fill" style={{ width: `${Math.min(100, Math.max(0, queue.progressPercentage))}%` }} />
              </div>
              <span className="current-capture-remaining">{formatDuration(queue.overallRemainingTime)} left</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="current-empty">No capture running</p>
      )}

      <dl className="current-guiding">
        <dt>Guiding</dt>
        <dd>
          {latestGuideDelta
            ? <>RA {latestGuideDelta.ra.toFixed(2)}″ · DEC {latestGuideDelta.de.toFixed(2)}″{ditheringActive ? ' (dithering)' : ''}</>
            : '—'}
          {guideSigma && <> · RMS {Math.hypot(guideSigma.ra, guideSigma.de).toFixed(2)}″</>}
        </dd>
      </dl>
    </div>
  );
}
