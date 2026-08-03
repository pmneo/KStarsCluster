import { getJobStateLabel, type SchedulerJob } from '../api/types';

interface Props {
  schedulerState: string;
  activeJob: SchedulerJob | null;
  jobs: SchedulerJob[];
}

function formatTime(iso: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function SchedulerCard({ schedulerState, activeJob, jobs }: Props) {
  return (
    <div className="card card--wide">
      <h3>Scheduler</h3>
      <dl>
        <dt>State</dt>
        <dd>{schedulerState}</dd>
      </dl>
      {jobs.length > 0 && (
        <div className="table-scroll">
          <table className="scheduler-jobs">
            <thead>
              <tr>
                <th>Job</th>
                <th>State</th>
                <th>Progress</th>
                <th>Alt.</th>
                <th>Startup</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.name} className={job.name === activeJob?.name ? 'active-job' : ''}>
                  <td>{job.name}</td>
                  <td>{getJobStateLabel(job.state).replace(/^JOB_/, '')}</td>
                  {/* sequenceCount is already the job's total planned frame count (confirmed
                   * against live status: 960 for a job with 80 repeatsRequired) — repeatsRequired
                   * is a separate, unrelated number (how many times the sequence repeats), not a
                   * frame count, so pairing it with completedCount here was comparing unlike
                   * units (e.g. showed "692 / 80" when the real target was "692 / 960"). */}
                  <td>{job.completedCount} / {job.sequenceCount}</td>
                  <td>{job.altitude.toFixed(1)}°</td>
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
