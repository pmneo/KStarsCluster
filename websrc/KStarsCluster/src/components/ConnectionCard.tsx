import { actions } from '../api/actions';

interface Props {
  kstarsRunning: boolean;
  ekosReady: boolean;
  ekosStatus: string;
  manualStartRequested: boolean;
  automationSuspended: boolean;
}

export function ConnectionCard({ kstarsRunning, ekosReady, ekosStatus, manualStartRequested, automationSuspended }: Props) {
  const connectionLabel = ekosReady
    ? 'Ready'
    : !kstarsRunning
      ? 'KStars is not running'
      : `KStars running, Ekos not ready (${ekosStatus})`;

  return (
    <div className="card">
      <h3>Connection</h3>
      <dl>
        <dt>State</dt>
        <dd>{connectionLabel}</dd>
      </dl>
      <div className="actions">
        <button onClick={() => actions.connection.startEkos()} disabled={ekosReady || manualStartRequested}>
          {manualStartRequested ? 'Starting…' : 'Start Ekos / KStars'}
        </button>
        {automationSuspended
          ? <button onClick={() => actions.connection.resume()}>Resume</button>
          : <button onClick={() => actions.connection.suspend()}>Suspend</button>}
        <button onClick={() => { if (confirm('Stop KStars?')) actions.connection.stopKStars(); }} disabled={!kstarsRunning}>
          Stop KStars
        </button>
      </div>
    </div>
  );
}
