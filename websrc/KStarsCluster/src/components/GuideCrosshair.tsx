import type { GuideDeltaSample } from '../api/types';

interface Props {
  samples: GuideDeltaSample[];
  sigma?: { ra: number; de: number };
}

const SIZE = 240;
const MAX_POINTS = 200;

/** Ekos' own guide "drift" plot: dRA (x) vs dDE (y) as a target-style scatter instead of a time
 * series (see GuideChart) — shows the error's actual spread and any directional bias at a
 * glance, which a plain over-time chart can't. N/S are unambiguous (declination increases
 * north); E/W are safe too — Right Ascension increases eastward by definition, and Ekos
 * calibrates its RA guide axis against the sky before guiding starts specifically so a positive
 * RA error here really does mean "drifted east", not just "needs correction in this direction". */
export function GuideCrosshair({ samples, sigma }: Props) {
  if (samples.length === 0) {
    return <div className="hfr-chart-empty">No guide samples yet</div>;
  }

  const recent = samples.slice(-MAX_POINTS);
  const maxAbs = Math.max(2, ...recent.flatMap((s) => [Math.abs(s.ra), Math.abs(s.de)]));
  const range = maxAbs * 1.2;
  const cx = SIZE / 2;
  const cy = SIZE / 2;
  const scale = (SIZE / 2 - 14) / range;
  const halfTick = range / 2;

  function px(ra: number, de: number): [number, number] {
    // +ra plots right (east), +de plots up (north) — see the note above.
    return [cx + ra * scale, cy - de * scale];
  }

  const rms = sigma ? Math.hypot(sigma.ra, sigma.de) : undefined;

  return (
    <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width={SIZE} height={SIZE} role="img" aria-label="Guide error crosshair">
      <line x1={cx - halfTick * scale} y1={0} x2={cx - halfTick * scale} y2={SIZE} stroke="#2a2f3d" strokeDasharray="2,3" />
      <line x1={cx + halfTick * scale} y1={0} x2={cx + halfTick * scale} y2={SIZE} stroke="#2a2f3d" strokeDasharray="2,3" />
      <line x1={0} y1={cy - halfTick * scale} x2={SIZE} y2={cy - halfTick * scale} stroke="#2a2f3d" strokeDasharray="2,3" />
      <line x1={0} y1={cy + halfTick * scale} x2={SIZE} y2={cy + halfTick * scale} stroke="#2a2f3d" strokeDasharray="2,3" />

      <line x1={cx} y1={0} x2={cx} y2={SIZE} stroke="#4b5163" />
      <line x1={0} y1={cy} x2={SIZE} y2={cy} stroke="#4b5163" />

      {/* Rings derived from this camera's own current RMS (we have no access to Ekos' configured
       * good/warn thresholds), so they read as "typical spread" / "2x typical spread" rather than
       * fixed quality zones. */}
      {rms != null && rms * scale < SIZE / 2 && (
        <circle cx={cx} cy={cy} r={rms * scale} fill="none" stroke="#4ade80" strokeDasharray="3,3" opacity={0.7} />
      )}
      {rms != null && rms * 2 * scale < SIZE / 2 && (
        <circle cx={cx} cy={cy} r={rms * 2 * scale} fill="none" stroke="#facc15" strokeDasharray="3,3" opacity={0.5} />
      )}

      {recent.map((s, i) => {
        const [x, y] = px(s.ra, s.de);
        const isLatest = i === recent.length - 1;
        const age = recent.length <= 1 ? 1 : i / (recent.length - 1);
        return (
          <circle
            key={`${s.ts}-${i}`}
            cx={x}
            cy={y}
            r={isLatest ? 3 : 1.6}
            fill={isLatest ? '#f87171' : '#4dabf7'}
            opacity={isLatest ? 1 : 0.15 + age * 0.5}
          />
        );
      })}

      <text x={cx} y={10} textAnchor="middle" fontSize="9" fill="#8a8f9c">N</text>
      <text x={cx} y={SIZE - 3} textAnchor="middle" fontSize="9" fill="#8a8f9c">S</text>
      <text x={SIZE - 3} y={cy + 3} textAnchor="end" fontSize="9" fill="#8a8f9c">E</text>
      <text x={3} y={cy + 3} textAnchor="start" fontSize="9" fill="#8a8f9c">W</text>
      <text x={3} y={SIZE - 3} textAnchor="start" fontSize="8" fill="#5b6070">±{range.toFixed(1)}″</text>
    </svg>
  );
}
