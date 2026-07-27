export interface HfrSample {
  ts: number;
  hfr: number;
  position: number;
}

/** Guide.newAxisDelta — one sample per guide frame, arcsec RA/DEC guiding error. */
export interface GuideDeltaSample {
  ts: number;
  ra: number;
  de: number;
}

export interface SchedulerJob {
  name: string;
  altitude: number;
  completedCount: number;
  completionTime: string;
  inSequenceFocus: boolean;
  minAltitude: number;
  minMoonSeparation: number;
  pa: number;
  repeatsRemaining: number;
  repeatsRequired: number;
  sequence: string;
  sequenceCount: number;
  stage: number;
  startupTime: string;
  state: number;
  targetDEC: number;
  targetRA: number;
  fRatio: number;
}

/** Mirrors org.kde.kstars.ekos.SchedulerJob.JobState's ordinal order (SchedulerJob.java). */
const JOB_STATE_LABELS = [
  'JOB_IDLE', 'JOB_EVALUATION', 'JOB_SCHEDULED', 'JOB_BUSY',
  'JOB_ERROR', 'JOB_ABORTED', 'JOB_INVALID', 'JOB_COMPLETE',
];

export function getJobStateLabel(state: number): string {
  return JOB_STATE_LABELS[state] ?? `JOB_STATE_${state}`;
}

export interface CapturedImage {
  ts: number;
  filename: string;
  filter: string;
  exposure: number;
  hfr: number;
  eccentricity: number;
  median: number;
  starCount: number;
  width: number;
  height: number;
  type: number;
}

/** INDI CCDChip::CCDFrameType order — confirmed against a real captureComplete signal (Flat frame -> type 3). */
const FRAME_TYPE_LABELS = ['Light', 'Bias', 'Dark', 'Flat'];

export function getFrameTypeLabel(type: number): string {
  return FRAME_TYPE_LABELS[type] ?? `Type ${type}`;
}

/** One planned exposure step from Capture.getSequenceQueueStatusJSON — numeric fields ride as
 * locale-formatted strings straight from KStars (e.g. "3,000000" under a German locale). */
export interface SequenceStep {
  Type: string;
  Filter: string;
  Count: string;
  Exp: string;
  Bin: string;
  Format: string;
  Encoding: string;
  'ISO/Gain': string;
  Offset: string;
  Temperature: number;
  EnforceTemperature: boolean;
  DitherPerJobEnabled: boolean;
  DitherPerJobFrequency: number;
  Status: string;
}

/** Capture.getSequenceQueueStatusJSON(train) — the same detail Ekos's own Capture module shows. */
export interface SequenceQueueStatus {
  train: string;
  camera: string;
  status: string;
  jobCount: number;
  pendingJobCount: number;
  progressPercentage: number;
  overallRemainingTime: number;
  activeJobID: number;
  activeJobState: string;
  activeJobFilterName: string;
  activeJobExposureProgress: number;
  activeJobExposureDuration: number;
  activeJobImageProgress: number;
  activeJobImageCount: number;
  activeJobRemainingTime: number;
  sequence: SequenceStep[];
}

/** KStars formats these as locale strings (German: "3,000000") — parse leniently either way. */
export function parseLocaleNumber(value: string | number | undefined): number {
  if (typeof value === 'number') return value;
  if (!value) return NaN;
  return Number(value.replace(',', '.'));
}

export interface AlignmentInfo {
  solutionResult: number[] | null;
  pa?: number;
  ra?: string;
  dec?: string;
}

export interface DeviceInfo {
  name: string;
  temperature?: number;
  antiDewHeaterOn?: boolean;
  isCooling?: boolean;
  parked?: boolean;
  lightOn?: boolean;
  filters?: string[];
  currentFilter?: string;
}

export interface StatusSnapshot {
  kstarsRunning: boolean;
  ekosReady: boolean;
  ekosStatus: string;
  manualStartRequested: boolean;
  automationSuspended: boolean;
  captureRunning: Record<string, boolean>;
  focusRunning: Record<string, boolean>;
  gudingRunning: boolean;
  ditheringActive: boolean;
  alignStatus: string;
  weatherState: string;
  mountStatus: string;
  schedulerState: string;
  captureStatus: Record<string, string>;
  focusState: Record<string, string>;
  guideStatus: string;
  activeJob: SchedulerJob | null;
  jobs: SchedulerJob[];
  alignment: AlignmentInfo;
  roofStatus?: string;
  serverInfo?: unknown;
  hfrHistory: Record<string, HfrSample[]>;
  images: Record<string, CapturedImage[]>;
  sequenceQueue: Record<string, SequenceQueueStatus>;
  /** Mount.equatorialCoords — RA in decimal hours, DEC in decimal degrees. Absent until the first read succeeds. */
  mountCoords?: { ra: number; dec: number };
  /** Align.fov — the active camera+telescope's actual field of view. Absent until the first read succeeds. */
  fov?: { widthArcmin: number; heightArcmin: number };
  guideDeltaHistory: GuideDeltaSample[];
  /** Guide.newAxisSigma — latest RA/DEC guiding RMS, in arcsec. Absent until the first signal arrives. */
  guideSigma?: { ra: number; de: number };
  [key: string]: unknown;
}

const KNOWN_STATUS_KEYS = new Set([
  'kstarsRunning', 'ekosReady', 'ekosStatus', 'manualStartRequested',
  'automationSuspended', 'captureRunning', 'focusRunning', 'gudingRunning',
  'ditheringActive', 'alignStatus', 'weatherState', 'mountStatus',
  'schedulerState', 'captureStatus', 'focusState', 'guideStatus',
  'activeJob', 'jobs', 'alignment', 'roofStatus', 'serverInfo',
  'hfrHistory', 'images', 'sequenceQueue', 'mountCoords', 'fov',
  'guideDeltaHistory', 'guideSigma',
]);

/** Trains aren't a fixed set on the backend — derive them from whichever per-train maps are present. */
export function getTrains(status: StatusSnapshot): string[] {
  const trains = new Set<string>();
  Object.keys(status.captureStatus ?? {}).forEach((t) => trains.add(t));
  Object.keys(status.focusState ?? {}).forEach((t) => trains.add(t));
  return Array.from(trains).sort();
}

/** Most recent capture across all trains, for the sky map's "last image" overlay. */
export function getLastImageFilename(status: StatusSnapshot): string | undefined {
  let latest: CapturedImage | undefined;
  for (const images of Object.values(status.images ?? {})) {
    for (const img of images) {
      if (!latest || img.ts > latest.ts) {
        latest = img;
      }
    }
  }
  return latest?.filename;
}

/** Filter wheels / cameras / caps are flat top-level keys named after their INDI device name. */
export function getDevices(status: StatusSnapshot): DeviceInfo[] {
  return Object.entries(status)
    .filter(([key]) => !KNOWN_STATUS_KEYS.has(key))
    .map(([name, value]) => ({ name, ...(value as object) }) as DeviceInfo);
}
