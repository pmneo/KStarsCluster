import type { DeviceInfo } from '../api/types';

export function DeviceList({ devices }: { devices: DeviceInfo[] }) {
  if (devices.length === 0) return null;

  return (
    <div className="card">
      <h3>Devices</h3>
      <table>
        <tbody>
          {devices.map((d) => (
            <tr key={d.name}>
              <td>{d.name}</td>
              <td>
                {d.currentFilter && <>filter: {d.currentFilter} </>}
                {d.temperature !== undefined && <>temp: {d.temperature.toFixed(1)}°C </>}
                {d.isCooling !== undefined && <>{d.isCooling ? 'cooling' : 'warm'} </>}
                {d.parked !== undefined && <>{d.parked ? 'parked' : 'unparked'} </>}
                {d.lightOn !== undefined && <>light {d.lightOn ? 'on' : 'off'}</>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
