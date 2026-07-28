import { useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { CategoricalChartState } from 'recharts/types/chart/types';
import type { HfrSample } from '../api/types';

/** Samples within one autofocus run land seconds apart; separate runs are minutes to hours
 * apart (next scheduled refocus, temperature trigger, ...) — comfortably splittable on a gap
 * this size regardless of whether the samples came from a live run or the restored-at-startup
 * analyze log (where every sample in one run shares the exact same timestamp). */
const RUN_GAP_MS = 60_000;

function groupIntoRuns(samples: HfrSample[]): HfrSample[][] {
  const sorted = [...samples].sort((a, b) => a.ts - b.ts);
  const runs: HfrSample[][] = [];

  for (const s of sorted) {
    const run = runs[runs.length - 1];
    if (run && s.ts - run[run.length - 1].ts <= RUN_GAP_MS) {
      run.push(s);
    } else {
      runs.push([s]);
    }
  }

  return runs;
}

/** How many runs feed the trend line on the right — overlaying that many full V-curves instead
 * (the earlier approach) was too cluttered to read; one point per run stays legible. */
const TREND_RUNS_SHOWN = 20;

export function HfrChart({ samples }: { samples: HfrSample[] }) {
  // Index into trendRuns (not runs) of the run currently hovered on the trend chart — null
  // means "nothing hovered", so the V-curve panel falls back to the latest run.
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  if (samples.length === 0) {
    return <div className="hfr-chart-empty">No HFR samples yet</div>;
  }

  const runs = groupIntoRuns(samples);
  const trendRuns = runs.slice(-TREND_RUNS_SHOWN);
  const trend = trendRuns.map((run) => ({
    time: new Date(run[0].ts).toLocaleTimeString(),
    minHfr: Math.min(...run.map((s) => s.hfr)),
  }));

  const selectedRun = hoveredIndex != null ? trendRuns[hoveredIndex] : runs[runs.length - 1];
  const displayedRun = [...selectedRun].sort((a, b) => a.position - b.position);
  const isLatest = selectedRun === runs[runs.length - 1];

  function handleTrendHover(state: CategoricalChartState) {
    setHoveredIndex(state.isTooltipActive && state.activeTooltipIndex != null ? state.activeTooltipIndex : null);
  }

  return (
    <div className="hfr-chart-row">
      {/* Ekos' own focus graph: one run as position (X) vs HFR (Y), the V/U-shaped curve the
       * focuser sweeps to find the minimum. Only one run at a time, not all of them overlaid —
       * that read as a cluttered smear once a session had more than a couple of refocus cycles.
       * Defaults to the latest run; hovering a point on the trend chart swaps in that run instead. */}
      <div className="hfr-chart-col">
        <div className="hfr-chart-label">{isLatest ? 'Latest focus run' : `Focus run @ ${new Date(selectedRun[0].ts).toLocaleTimeString()}`}</div>
        <ResponsiveContainer width="100%" height={140}>
          <LineChart data={displayedRun}>
            <XAxis dataKey="position" type="number" domain={['dataMin', 'dataMax']} tick={{ fontSize: 10 }} />
            <YAxis dataKey="hfr" tick={{ fontSize: 10 }} width={30} />
            <Tooltip formatter={(value: number) => Number(value).toFixed(2)} labelFormatter={(position) => `position ${position}`} />
            <Line type="monotone" dataKey="hfr" stroke="#4dabf7" dot={{ r: 2 }} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      {/* Complements the V-curve with the thing it can't show: how focus quality (the minimum
       * HFR each run settled on) trends across the session — one point per run, not per sample.
       * Hovering a point here drives which run's V-curve shows on the left. */}
      <div className="hfr-chart-col">
        <div className="hfr-chart-label">Focus trend (best HFR per run)</div>
        <ResponsiveContainer width="100%" height={140}>
          <LineChart data={trend} onMouseMove={handleTrendHover} onMouseLeave={() => setHoveredIndex(null)}>
            <XAxis dataKey="time" tick={{ fontSize: 10 }} minTickGap={30} />
            <YAxis dataKey="minHfr" tick={{ fontSize: 10 }} width={30} />
            <Tooltip formatter={(value: number) => Number(value).toFixed(2)} />
            <Line type="monotone" dataKey="minHfr" stroke="#f59e0b" dot={{ r: 2 }} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
