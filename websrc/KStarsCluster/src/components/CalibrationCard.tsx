import { useState } from 'react';
import { actions } from '../api/actions';

export function CalibrationCard() {
  const [angles, setAngles] = useState('90,180,270');

  return (
    <div className="card">
      <h3>Calibration</h3>
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
