import { actions } from '../api/actions';

export function CoolingCard() {
  return (
    <div className="card">
      <h3>Cooling</h3>
      <div className="actions">
        <button onClick={() => actions.cooling.preCool()}>Pre-Cool</button>
        <button onClick={() => actions.cooling.warmCameras()}>Warm Cameras</button>
      </div>
    </div>
  );
}
