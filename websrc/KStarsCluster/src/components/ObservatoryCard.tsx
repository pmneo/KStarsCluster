import { actions } from '../api/actions';
import { SafetyLock } from './SafetyLock';

interface Props {
  roofStatus?: string;
}

export function ObservatoryCard({ roofStatus }: Props) {
  return (
    <div className="card">
      <h3>Observatory</h3>
      <SafetyLock label="observatory controls">
        {roofStatus !== undefined && (
          <div className="actions">
            <button onClick={() => actions.observatory.roofOpen()}>Open Roof</button>
            <button onClick={() => actions.observatory.roofClose()}>Close Roof</button>
          </div>
        )}
        {roofStatus && <p className="roof-status">Roof: {roofStatus}</p>}

        {/* Light and cap controls are further down — flipping either while a capture is running
         * ruins the exposure, and they're reached for far less often than the roof. */}
        <div className="actions">
          <button onClick={() => actions.observatory.lightOn()}>Light On</button>
          <button onClick={() => actions.observatory.lightOff()}>Light Off</button>
        </div>
        <div className="actions">
          <button onClick={() => actions.observatory.capOpen()}>Open Cap</button>
          <button onClick={() => actions.observatory.capClose()}>Close Cap</button>
        </div>
      </SafetyLock>
    </div>
  );
}
