import { getJobStateLabel, type SchedulerJob } from '../api/types';

interface Props {
  schedulerState: string;
  activeJob: SchedulerJob | null;
  jobs: SchedulerJob[];
  ekosReady: boolean;
  /** Jobs parsed straight off the configured .esl file (see fetchScheduleFileJobs) — shown instead
   * of `jobs` whenever Ekos isn't up to report live ones, so the schedule stays visible across a
   * KStars restart instead of going blank. A freshly-parsed job has no run history (always
   * JOB_IDLE, completedCount 0, altitude 0), so the table below repurposes the columns that would
   * otherwise just show misleading zeroes. */
  plannedJobs: SchedulerJob[];
}

function formatTime(iso: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function SchedulerCard({ schedulerState, activeJob, jobs, ekosReady, plannedJobs }: Props) {
  const showingPlanned = !ekosReady;
  const displayJobs = showingPlanned ? plannedJobs : jobs;
  return (
    <div className="card card--wide">
      <h3>
        Scheduler
        {showingPlanned && <span className="scheduler-mode-note"> · planned — Ekos not connected</span>}
      </h3>
      <dl>
        <dt>State</dt>
        <dd>{schedulerState}</dd>
      </dl>
      {displayJobs.length > 0 && (
        <div className="table-scroll">
          <table className="scheduler-jobs">
            <thead>
              <tr>
                <th>Job</th>
                <th>{showingPlanned ? 'Train' : 'State'}</th>
                <th>Progress</th>
                <th>Alt.</th>
                <th>Startup</th>
              </tr>
            </thead>
            <tbody>
              {displayJobs.map((job, i) => (
                <tr key={`${job.name}-${i}`} className={job.name === activeJob?.name ? 'active-job' : ''}>
                  <td>{job.name}</td>
                  <td>{showingPlanned ? (job.opticalTrain ?? '—') : getJobStateLabel(job.state).replace(/^JOB_/, '')}</td>
                  {/* sequenceCount is already the job's total planned frame count (confirmed
                   * against live status: 960 for a job with 80 repeatsRequired) — repeatsRequired
                   * is a separate, unrelated number (how many times the sequence repeats), not a
                   * frame count, so pairing it with completedCount here was comparing unlike
                   * units (e.g. showed "692 / 80" when the real target was "692 / 960"). A freshly
                   * parsed planned job has no completedCount at all, so that side shows "—" instead
                   * of a misleading "0". */}
                  <td>{showingPlanned ? '—' : job.completedCount} / {job.sequenceCount}</td>
                  <td>{showingPlanned ? '—' : `${job.altitude.toFixed(1)}°`}</td>
                  <td>{formatTime(job.startupTime)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
