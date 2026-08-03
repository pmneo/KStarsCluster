import { LineChart, Line, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { GuideDeltaSample } from '../api/types';

export function GuideChart({ samples }: { samples: GuideDeltaSample[] }) {
  if (samples.length === 0) {
    return <div className="hfr-chart-empty">No guide samples yet</div>;
  }

  const data = samples.map((s) => ({ ...s, time: new Date(s.ts).toLocaleTimeString() }));

  return (
    <ResponsiveContainer width="100%" height={160}>
      <LineChart data={data}>
        <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
        <YAxis tick={{ fontSize: 10 }} width={30} />
        <Tooltip formatter={(value: number) => value.toFixed(2)} />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <Line type="monotone" dataKey="ra" name="RA" stroke="#4dabf7" dot={false} isAnimationActive={false} />
        <Line type="monotone" dataKey="de" name="DEC" stroke="#f59e0b" dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
