import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { HfrSample } from '../api/types';

export function HfrChart({ samples }: { samples: HfrSample[] }) {
  if (samples.length === 0) {
    return <div className="hfr-chart-empty">No HFR samples yet</div>;
  }

  const data = samples.map((s) => ({ ...s, time: new Date(s.ts).toLocaleTimeString() }));

  return (
    <ResponsiveContainer width="100%" height={160}>
      <LineChart data={data}>
        <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
        <YAxis dataKey="hfr" tick={{ fontSize: 10 }} width={30} />
        <Tooltip formatter={(value: number) => value.toFixed(2)} />
        <Line type="monotone" dataKey="hfr" stroke="#4dabf7" dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
