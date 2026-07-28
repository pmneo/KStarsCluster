import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { AllskyPoint } from '../api/allskyApi';

export function AllskyChart({ points }: { points: AllskyPoint[] }) {
  if (points.length === 0) {
    return <div className="hfr-chart-empty">No allsky samples yet</div>;
  }

  const data = points.map((p) => ({ ...p, time: new Date(p.ts).toLocaleTimeString() }));

  return (
    <ResponsiveContainer width="100%" height={100}>
      <LineChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
        <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
        <YAxis tick={{ fontSize: 10 }} width={30} />
        <Tooltip formatter={(value: number) => value.toFixed(0)} />
        <Line type="monotone" dataKey="stars" name="Stars" stroke="#4dabf7" dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
