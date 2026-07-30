import { useRef, useState } from 'react';
import type { CapturedImage, GuideDeltaSample, HfrSample, TimelineEvent, TimelineCaptureSelection, ViewerImage } from '../api/types';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import type { AllskyMatch } from '../api/allskyApi';
import { CaptureCompareStrip } from './CaptureCompareStrip';

interface Props {
  images: Record<string, CapturedImage[]>;
  hfrHistory: Record<string, HfrSample[]>;
  guideDeltaHistory: GuideDeltaSample[];
  timelineEvents: TimelineEvent[];
  /** Transient — fires on every capture-segment hover, and with null on mouse-leave (see
   * handleSegmentLeave). Drives the live preview below the timeline; falls back to
   * onSelectCapture's last pin once the mouse leaves. */
  onHoverCapture: (selection: TimelineCaptureSelection | null) => void;
  /** Persistent — fires on a capture-segment click, pinning the compare strip below the timeline
   * to this capture (plus its nearest allsky matches) until another one is clicked or it's
   * explicitly cleared. */
  onSelectCapture: (selection: TimelineCaptureSelection) => void;
  /** hoveredCapture ?? pinnedCapture, computed in App — whichever is currently active is what
   * CaptureCompareStrip below renders. */
  activeCapture: TimelineCaptureSelection | null;
  activeAllskyMatches: AllskyMatch[];
  onClearActiveCapture: () => void;
  onOpenImage: (image: ViewerImage) => void;
}

/** Validated (node scripts/validate_palette.js, dark mode, surface #1c1f28 — this app's --panel)
 * categorical palette, assigned in first-seen order per identity domain (filter name, train name,
 * job name) rather than hashed, so colors stay stable as new categories appear over a session. */
const CATEGORICAL = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'];
/** Fixed status meaning — good/warning/critical never double as a series color, so a status lane
 * (guide/mount/align/scheduler-busy) never gets confused with a categorical one (filter/job). */
const STATUS_GOOD = '#0ca30c';
const STATUS_WARNING = '#fab219';
const STATUS_CRITICAL = '#d03b3b';
const IDLE = '#4a4f5e';
const FOCUS_MARK = '#9085e9';

function categoricalColor(seen: Map<string, string>, key: string): string {
  let color = seen.get(key);
  if (!color) {
    color = CATEGORICAL[seen.size % CATEGORICAL.length];
    seen.set(key, color);
  }
  return color;
}

function guideColor(label: string): string {
  if (label === 'GUIDE_GUIDING') return STATUS_GOOD;
  if (['GUIDE_DITHERING', 'GUIDE_MANUAL_DITHERING', 'GUIDE_CALIBRATING', 'GUIDE_REACQUIRE', 'GUIDE_DITHERING_SETTLE'].includes(label)) return STATUS_WARNING;
  if (['GUIDE_ABORTED', 'GUIDE_CALIBRATION_ERROR', 'GUIDE_DITHERING_ERROR'].includes(label)) return STATUS_CRITICAL;
  return IDLE;
}

function mountColor(label: string): string {
  if (label === 'MOUNT_TRACKING') return STATUS_GOOD;
  if (['MOUNT_SLEWING', 'MOUNT_MOVING', 'MOUNT_PARKING'].includes(label)) return STATUS_WARNING;
  if (label === 'MOUNT_ERROR') return STATUS_CRITICAL;
  return IDLE;
}

function alignColor(label: string): string {
  if (['ALIGN_SUCCESSFUL', 'ALIGN_COMPLETE'].includes(label)) return STATUS_GOOD;
  if (['ALIGN_PROGRESS', 'ALIGN_SLEWING', 'ALIGN_SYNCING', 'ALIGN_ROTATING'].includes(label)) return STATUS_WARNING;
  if (['ALIGN_FAILED', 'ALIGN_ABORTED'].includes(label)) return STATUS_CRITICAL;
  return IDLE;
}

/** Unlike MOUNT_TRACKING/GUIDE_GUIDING, "aligned successfully" isn't an ongoing state that keeps
 * being true until something else happens — it's a one-off event marking the align run's end,
 * same as an abort/failure — see toSegments' momentaryLabels. */
const ALIGN_MOMENTARY_LABELS = new Set(['ALIGN_SUCCESSFUL', 'ALIGN_COMPLETE']);

interface Segment {
  key: string;
  start: number;
  end: number;
  color: string;
  opacity: number;
  title: string;
  /** Only set for Capture segments — lets the hover tooltip show the actual frame, and hover/click
   * populate that train's ImageStrip with it (plus its nearest allsky matches). */
  viewerImage?: ViewerImage;
  /** Which train's Capture row this segment belongs to — only set alongside viewerImage. */
  train?: string;
}

/** Turns one lane's state-change events into contiguous segments: each event normally lasts
 * until the next one on the same lane, and the last one runs to `now` (it's still the current
 * state) — EXCEPT a critical (aborted/error/failed) event, or one of `momentaryLabels`, which is
 * a "this happened here" moment rather than a state that meaningfully persists (align completing
 * is the same kind of moment as align aborting — both mark the end of the align run, neither is
 * an ongoing state the way MOUNT_TRACKING or GUIDE_GUIDING are). Rendered as a zero-duration
 * segment instead: still visible (the row rendering clamps every segment to a minimum width), but
 * as a short marker rather than a long bar reaching all the way to whatever comes next. */
function toSegments( events: TimelineEvent[], lane: string, now: number, colorFor: (label: string) => string, momentaryLabels?: Set<string> ): Segment[] {
  const laneEvents = events.filter((e) => e.lane === lane).sort((a, b) => a.ts - b.ts);
  return laneEvents.map((e, i) => {
    const color = colorFor(e.label);
    const isMomentary = color === STATUS_CRITICAL || momentaryLabels?.has(e.label);
    const naturalEnd = i + 1 < laneEvents.length ? laneEvents[i + 1].ts : now;
    return {
      key: `${lane}-${e.ts}`,
      start: e.ts,
      end: isMomentary ? e.ts : naturalEnd,
      color,
      opacity: 1,
      title: `${e.label}  (${new Date(e.ts).toLocaleTimeString()})`,
    };
  });
}

function schedulerSegments( events: TimelineEvent[], seen: Map<string, string>, now: number ): Segment[] {
  const laneEvents = events.filter((e) => e.lane === 'scheduler').sort((a, b) => a.ts - b.ts);
  return laneEvents.map((e, i) => {
    const isIdle = e.label === 'idle';
    const jobName = isIdle ? '' : e.label.replace(/\s*\([^)]*\)$/, '');
    const isBusy = e.label.includes('(JOB_BUSY)');
    return {
      key: `scheduler-${e.ts}`,
      start: e.ts,
      end: i + 1 < laneEvents.length ? laneEvents[i + 1].ts : now,
      color: isIdle ? IDLE : categoricalColor(seen, jobName),
      opacity: isIdle || isBusy ? 1 : 0.45,
      title: `${e.label}  (${new Date(e.ts).toLocaleTimeString()})`,
    };
  });
}

/** Fixed by filter identity instead of first-seen order — these are conventional in narrowband/
 * broadband astrophotography (Ha/[SII]/[OIII] especially), so a fixed mapping reads correctly at
 * a glance instead of shifting depending on which filter happened to capture first. Light/dark
 * variants within the red family (Ha/[SII]/R) are a deliberate lightness ramp, not an oversight —
 * that's exactly how the user distinguishes them. Anything not listed here (an unusual filter
 * name) still falls back to the dynamic categorical assignment. */
const FIXED_FILTER_COLORS: Record<string, string> = {
  L: '#f5f5f5',
  R: '#e5484d',
  G: '#30a46c',
  B: '#3987e5',
  Ha: '#7f1d1d',
  SII: '#fca5a5',
  OIII: '#2dd4bf',
};

function filterColor(filter: string, dynamicSeen: Map<string, string>, legend: Map<string, string>): string {
  const color = FIXED_FILTER_COLORS[filter] ?? categoricalColor(dynamicSeen, filter);
  if (!legend.has(filter)) {
    legend.set(filter, color);
  }
  return color;
}

function captureSegments( imgs: CapturedImage[], train: string, dynamicSeen: Map<string, string>, legend: Map<string, string> ): Segment[] {
  return imgs.map((img) => ({
    key: `capture-${img.filename}`,
    start: img.ts - img.exposure * 1000,
    end: img.ts,
    color: filterColor(img.filter, dynamicSeen, legend),
    opacity: 1,
    title: `${img.filter} ${img.exposure}s${img.target ? ` · ${img.target}` : ''}  (${new Date(img.ts).toLocaleTimeString()})`,
    viewerImage: { filename: img.filename, target: img.target, filter: img.filter, exposure: img.exposure },
    train,
  }));
}

/** Same run-grouping idea as HfrChart: samples seconds apart belong to one autofocus run, runs
 * are minutes to hours apart — one segment per run instead of per sample. */
const RUN_GAP_MS = 60_000;

function focusSegments( samples: HfrSample[] ): Segment[] {
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

  return runs.map((run) => ({
    key: `focus-${run[0].ts}`,
    start: run[0].ts,
    end: run[run.length - 1].ts,
    color: FOCUS_MARK,
    opacity: 1,
    title: `Autofocus (${run.length} points, best HFR ${Math.min(...run.map((s) => s.hfr)).toFixed(2)})  (${new Date(run[0].ts).toLocaleTimeString()})`,
  }));
}

const WIDTH = 1000;
const ROW_HEIGHT = 22;
const ROW_GAP = 4;
const LABEL_WIDTH = 110;

/** Default view before the user has touched the scrollbar — the last 24h, right edge pinned to
 * "now". Once they drag either handle, the view holds still at whatever absolute range they
 * picked instead of continuing to track "now" (see viewStart/viewEnd below). */
const DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1000;
/** Narrowest the brush can be dragged to — small enough to zoom into a single autofocus run,
 * not so small a handle-drag could collapse it to an unreadable sliver. */
const MIN_SPAN_MS = 5 * 60 * 1000;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

interface Row {
  label: string;
  segments: Segment[];
}

interface HoverState {
  x: number;
  y: number;
  title: string;
  thumbnail?: string;
}

type DragMode = 'start' | 'end' | 'pan';

interface DragState {
  mode: DragMode;
  startClientX: number;
  startViewStart: number;
  startViewEnd: number;
}

/** Premiere-style scrollbar: a track spanning the full available range, a highlighted band for
 * the currently viewed range, and two handles at its ends. Dragging a handle resizes the view
 * from that end; dragging the band itself pans without resizing. Plain pointer events instead of
 * an <input type="range"> pair — two independent single-thumb sliders can't express "drag the
 * band between them to pan" or render the highlighted span itself. */
function TimelineScrollbar({ fullStart, fullEnd, viewStart, viewEnd, onChange }: {
  fullStart: number; fullEnd: number; viewStart: number; viewEnd: number;
  onChange: (start: number, end: number) => void;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<DragState | null>(null);

  const fullSpan = Math.max(1, fullEnd - fullStart);
  const pctStart = clamp(((viewStart - fullStart) / fullSpan) * 100, 0, 100);
  const pctEnd = clamp(((viewEnd - fullStart) / fullSpan) * 100, 0, 100);

  function beginDrag(mode: DragMode) {
    return (e: React.PointerEvent) => {
      e.preventDefault();
      dragRef.current = { mode, startClientX: e.clientX, startViewStart: viewStart, startViewEnd: viewEnd };

      function onMove(ev: PointerEvent) {
        const drag = dragRef.current;
        const track = trackRef.current;
        if (!drag || !track || track.clientWidth === 0) return;

        const deltaMs = ((ev.clientX - drag.startClientX) / track.clientWidth) * fullSpan;
        if (drag.mode === 'start') {
          onChange(clamp(drag.startViewStart + deltaMs, fullStart, drag.startViewEnd - MIN_SPAN_MS), drag.startViewEnd);
        } else if (drag.mode === 'end') {
          onChange(drag.startViewStart, clamp(drag.startViewEnd + deltaMs, drag.startViewStart + MIN_SPAN_MS, fullEnd));
        } else {
          const span = drag.startViewEnd - drag.startViewStart;
          const start = clamp(drag.startViewStart + deltaMs, fullStart, fullEnd - span);
          onChange(start, start + span);
        }
      }

      function onUp() {
        dragRef.current = null;
        window.removeEventListener('pointermove', onMove);
        window.removeEventListener('pointerup', onUp);
      }

      window.addEventListener('pointermove', onMove);
      window.addEventListener('pointerup', onUp);
    };
  }

  return (
    <div className="timeline-scrollbar" ref={trackRef}>
      <div
        className="timeline-scrollbar-band"
        style={{ left: `${pctStart}%`, width: `${Math.max(0, pctEnd - pctStart)}%` }}
        onPointerDown={beginDrag('pan')}
      />
      <div className="timeline-scrollbar-handle" style={{ left: `${pctStart}%` }} onPointerDown={beginDrag('start')} />
      <div className="timeline-scrollbar-handle" style={{ left: `${pctEnd}%` }} onPointerDown={beginDrag('end')} />
    </div>
  );
}

export function SessionTimeline({
  images, hfrHistory, guideDeltaHistory, timelineEvents, onHoverCapture, onSelectCapture,
  activeCapture, activeAllskyMatches, onClearActiveCapture, onOpenImage,
}: Props) {
  // null = "follow now", i.e. the default last-24h view whose right edge keeps up with live
  // data. Set once the user drags a handle, at which point the view holds still at that exact
  // range instead of continuing to track "now" — matches how scrubbing a Premiere timeline
  // doesn't keep chasing the playhead either.
  const [viewRange, setViewRange] = useState<{ start: number; end: number } | null>(null);
  const [hover, setHover] = useState<HoverState | null>(null);
  const [thumbStretch, setThumbStretch] = useState<StretchSettings>(DEFAULT_STRETCH);
  const stretchCache = useRef(new Map<string, StretchSettings>());
  const hoveredThumbnailRef = useRef<string | undefined>(undefined);

  const now = Date.now();

  const filterLegend = new Map<string, string>();
  const dynamicFilterSeen = new Map<string, string>();
  const jobColors = new Map<string, string>();

  const rows: Row[] = [];
  rows.push({ label: 'Scheduler', segments: schedulerSegments(timelineEvents, jobColors, now) });
  for (const train of Object.keys(images).sort()) {
    rows.push({ label: `Capture (${train})`, segments: captureSegments(images[train], train, dynamicFilterSeen, filterLegend) });
  }
  for (const train of Object.keys(hfrHistory).sort()) {
    rows.push({ label: `Focus (${train})`, segments: focusSegments(hfrHistory[train]) });
  }
  rows.push({ label: 'Guide', segments: toSegments(timelineEvents, 'guide', now, guideColor) });
  rows.push({ label: 'Mount', segments: toSegments(timelineEvents, 'mount', now, mountColor) });
  rows.push({ label: 'Align', segments: toSegments(timelineEvents, 'align', now, alignColor, ALIGN_MOMENTARY_LABELS) });

  const allTs = [
    ...rows.flatMap((r) => r.segments.flatMap((s) => [s.start, s.end])),
    ...guideDeltaHistory.map((s) => s.ts),
  ];

  if (allTs.length === 0) {
    return (
      <div className="card card--full">
        <h3>Session Timeline</h3>
        <div className="hfr-chart-empty">No session data yet</div>
      </div>
    );
  }

  function handleSegmentHover(e: React.MouseEvent, seg: Segment) {
    const filename = seg.viewerImage?.filename;
    hoveredThumbnailRef.current = filename;
    setHover({ x: e.clientX, y: e.clientY, title: seg.title, thumbnail: filename });

    if (seg.viewerImage && seg.train !== undefined) {
      onHoverCapture({ train: seg.train, ts: seg.end, image: seg.viewerImage });
    }
    if (!filename) return;

    const cached = stretchCache.current.get(filename);
    if (cached) {
      setThumbStretch(cached);
      return;
    }

    setThumbStretch(DEFAULT_STRETCH);
    fetchAutoStretch(filename, false)
      .then((s) => {
        stretchCache.current.set(filename, s);
        // the pointer may have moved to a different segment while this was in flight
        if (hoveredThumbnailRef.current === filename) {
          setThumbStretch(s);
        }
      })
      .catch(() => { /* leave the default stretch in place, no retry */ });
  }

  function handleSegmentMove(e: React.MouseEvent) {
    setHover((h) => (h ? { ...h, x: e.clientX, y: e.clientY } : h));
  }

  function handleSegmentLeave() {
    hoveredThumbnailRef.current = undefined;
    setHover(null);
    onHoverCapture(null);
  }

  function handleSegmentClick(seg: Segment) {
    if (seg.viewerImage && seg.train !== undefined) {
      onSelectCapture({ train: seg.train, ts: seg.end, image: seg.viewerImage });
    }
  }

  const fullStart = Math.min(...allTs);
  const fullEnd = now;
  const domainStart = viewRange ? viewRange.start : Math.max(fullStart, fullEnd - DEFAULT_WINDOW_MS);
  const domainEnd = viewRange ? viewRange.end : fullEnd;
  const plotWidth = WIDTH - LABEL_WIDTH;
  const scale = plotWidth / Math.max(1, domainEnd - domainStart);
  const x = (ts: number) => LABEL_WIDTH + Math.max(0, ts - domainStart) * scale;

  const height = rows.length * (ROW_HEIGHT + ROW_GAP) + 24;
  const tickCount = 6;
  const ticks = Array.from({ length: tickCount }, (_, i) => domainStart + ((domainEnd - domainStart) * i) / (tickCount - 1));

  const legend = [
    ...Array.from(filterLegend.entries()).map(([name, color]) => ({ name, color })),
    ...Array.from(jobColors.entries()).map(([name, color]) => ({ name, color })),
  ];

  return (
    <div className="card card--full">
      <h3>Session Timeline</h3>
      <div className="timeline-scrollbar-row">
        <TimelineScrollbar
          fullStart={fullStart}
          fullEnd={fullEnd}
          viewStart={domainStart}
          viewEnd={domainEnd}
          onChange={(start, end) => setViewRange({ start, end })}
        />
        <button type="button" className="timeline-zoom-btn" onClick={() => setViewRange(null)}>
          Reset
        </button>
      </div>
      {/* preserveAspectRatio="none": width is percentage (fills the card) but height is a fixed
       * pixel value (rows.length-driven, not tied to the card's aspect ratio) — the SVG default
       * (xMidYMid meet) treats that mismatch as "letterbox it", uniformly scaling by whichever
       * dimension is the tighter fit (height, since it's pinned) and centering the result, leaving
       * equal blank margins on both left and right instead of actually using the card's full
       * width. "none" stretches width and height independently instead, exactly filling the box. */}
      <svg viewBox={`0 0 ${WIDTH} ${height}`} width="100%" height={height} preserveAspectRatio="none" role="img" aria-label="Session timeline">
        {ticks.map((ts, i) => (
          <g key={i}>
            <line x1={x(ts)} y1={0} x2={x(ts)} y2={height - 20} stroke="#2c3040" strokeDasharray="2,3" />
            <text x={x(ts)} y={height - 6} fontSize="9" fill="#8b93a7" textAnchor="middle">
              {new Date(ts).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}
            </text>
          </g>
        ))}

        {rows.map((row, i) => {
          const y = i * (ROW_HEIGHT + ROW_GAP);
          return (
            <g key={row.label}>
              <text x={0} y={y + ROW_HEIGHT / 2 + 3} fontSize="10" fill="#e4e7ee">{row.label}</text>
              <rect x={LABEL_WIDTH} y={y} width={plotWidth} height={ROW_HEIGHT} fill="#14161c" />
              {row.segments.map((seg) => {
                const segX = x(seg.start);
                const segW = Math.max(1.5, x(seg.end) - segX);
                return (
                  <rect
                    key={seg.key}
                    x={segX}
                    y={y}
                    width={segW}
                    height={ROW_HEIGHT}
                    fill={seg.color}
                    opacity={seg.opacity}
                    style={seg.viewerImage ? { cursor: 'pointer' } : undefined}
                    onMouseEnter={(e) => handleSegmentHover(e, seg)}
                    onMouseMove={handleSegmentMove}
                    onMouseLeave={handleSegmentLeave}
                    onClick={() => handleSegmentClick(seg)}
                  />
                );
              })}
            </g>
          );
        })}
      </svg>

      {legend.length > 0 && (
        <div className="timeline-legend">
          {legend.map(({ name, color }) => (
            <span key={name} className="timeline-legend-item">
              <span className="timeline-legend-dot" style={{ backgroundColor: color }} />
              {name}
            </span>
          ))}
          <span className="timeline-legend-item">
            <span className="timeline-legend-dot" style={{ backgroundColor: FOCUS_MARK }} />
            Focus
          </span>
        </div>
      )}

      {activeCapture && (
        <CaptureCompareStrip
          selection={activeCapture}
          allskyMatches={activeAllskyMatches}
          onOpenImage={onOpenImage}
          onClear={onClearActiveCapture}
        />
      )}

      {hover && (
        <div className="timeline-tooltip" style={{ left: hover.x + 14, top: hover.y + 14 }}>
          {hover.thumbnail && (
            <img src={imageUrl(hover.thumbnail, 160, thumbStretch)} alt="" className="timeline-tooltip-thumb" />
          )}
          <div>{hover.title}</div>
        </div>
      )}
    </div>
  );
}
