import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { CategoricalChartState } from 'recharts/types/chart/types';
import type { AllskyPoint } from '../api/allskyApi';

interface Props {
  points: AllskyPoint[];
  /** Fires with the hovered point's image path (relative to the indi-allsky web root), or
   * undefined once the pointer leaves the chart — lets AllskyCard swap its background photo to
   * whatever was captured at that point in the star-count history. */
  onHover?: (url: string | undefined) => void;
}

export function AllskyChart({ points, onHover }: Props) {
  if (points.length === 0) {
    return <div className="hfr-chart-empty">No allsky samples yet</div>;
  }

  const data = points.map((p) => ({ ...p, time: new Date(p.ts).toLocaleTimeString() }));

  function handleMove(state: CategoricalChartState) {
    if (!onHover) return;
    const point = state.isTooltipActive && state.activeTooltipIndex != null ? data[state.activeTooltipIndex] : undefined;
    onHover(point?.url);
  }

  return (
    <ResponsiveContainer width="100%" height={100}>
      <LineChart
        data={data}
        margin={{ top: 4, right: 4, bottom: 0, left: -20 }}
        onMouseMove={handleMove}
        onMouseLeave={() => onHover?.(undefined)}
      >
        <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
        <YAxis tick={{ fontSize: 10 }} width={30} />
        <Tooltip formatter={(value: number) => value.toFixed(0)} />
        {/* Thick dark line underneath the real one acts as an outline, so the trace stays
         * legible over whatever brightness the photo behind the chart happens to be — without
         * a background box hiding the photo itself. */}
        <Line type="monotone" dataKey="stars" stroke="#000" strokeWidth={4} strokeOpacity={0.6} dot={false} isAnimationActive={false} legendType="none" />
        <Line type="monotone" dataKey="stars" name="Stars" stroke="#4dabf7" strokeWidth={1.5} dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
