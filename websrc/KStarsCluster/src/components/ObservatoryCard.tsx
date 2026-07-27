import { actions } from '../api/actions';

interface Props {
  roofStatus?: string;
}

export function ObservatoryCard({ roofStatus }: Props) {
  return (
    <div className="card">
      <h3>Observatory</h3>
      <div className="actions">
        {roofStatus !== undefined && (
          <>
            <button onClick={() => actions.observatory.roofOpen()}>Open Roof</button>
            <button onClick={() => actions.observatory.roofClose()}>Close Roof</button>
          </>
        )}
        <button onClick={() => actions.observatory.capOpen()}>Open Cap</button>
        <button onClick={() => actions.observatory.capClose()}>Close Cap</button>
        <button onClick={() => actions.observatory.lightOn()}>Light On</button>
        <button onClick={() => actions.observatory.lightOff()}>Light Off</button>
      </div>
      {roofStatus && <p className="roof-status">Roof: {roofStatus}</p>}
    </div>
  );
}
