import { useState } from 'react';
import { actions } from '../api/actions';

export function CoolingCalibrationCard() {
  const [angles, setAngles] = useState('90,180,270');

  return (
    <div className="card">
      <h3>Cooling &amp; Calibration</h3>
      <div className="actions">
        <button onClick={() => actions.cooling.preCool()}>Pre-Cool</button>
        <button onClick={() => actions.cooling.warmCameras()}>Warm Cameras</button>
      </div>
      <div className="autoflat">
        <label>
          AutoFlat angles
          <input value={angles} onChange={(e) => setAngles(e.target.value)} />
        </label>
        <button onClick={() => actions.calibration.autoFlat(angles.split(',').map(Number))}>Run AutoFlat</button>
      </div>
    </div>
  );
}
