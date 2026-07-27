import { LineChart, Line, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { AllskyPoint } from '../api/allskyApi';

export function AllskyChart({ points }: { points: AllskyPoint[] }) {
  if (points.length === 0) {
    return <div className="hfr-chart-empty">No allsky samples yet</div>;
  }

  const data = points.map((p) => ({ ...p, time: new Date(p.ts).toLocaleTimeString() }));

  return (
    <ResponsiveContainer width="100%" height={160}>
      <LineChart data={data}>
        <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
        <YAxis yAxisId="stars" tick={{ fontSize: 10 }} width={30} />
        <YAxis yAxisId="jsqm" orientation="right" tick={{ fontSize: 10 }} width={30} />
        <Tooltip formatter={(value: number) => value.toFixed(1)} />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <Line yAxisId="stars" type="monotone" dataKey="stars" name="Stars" stroke="#4dabf7" dot={false} isAnimationActive={false} />
        <Line yAxisId="jsqm" type="monotone" dataKey="jsqm" name="SQM" stroke="#f59e0b" dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
