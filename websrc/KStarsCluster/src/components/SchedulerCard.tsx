import { getJobStateLabel, type SchedulerJob } from '../api/types';
import { actions } from '../api/actions';

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
      <div className="actions">
        <button onClick={() => actions.scheduler.start()}>Start</button>
        <button onClick={() => actions.scheduler.stop()}>Stop</button>
      </div>
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
                  <td>{job.completedCount} / {job.repeatsRequired || job.sequenceCount}</td>
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
