export interface HfrSample {
  ts: number;
  hfr: number;
  position: number;
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
  [key: string]: unknown;
}

const KNOWN_STATUS_KEYS = new Set([
  'kstarsRunning', 'ekosReady', 'ekosStatus', 'manualStartRequested',
  'automationSuspended', 'captureRunning', 'focusRunning', 'gudingRunning',
  'ditheringActive', 'alignStatus', 'weatherState', 'mountStatus',
  'schedulerState', 'captureStatus', 'focusState', 'guideStatus',
  'activeJob', 'jobs', 'alignment', 'roofStatus', 'serverInfo',
]);

/** Trains aren't a fixed set on the backend — derive them from whichever per-train maps are present. */
export function getTrains(status: StatusSnapshot): string[] {
  const trains = new Set<string>();
  Object.keys(status.captureStatus ?? {}).forEach((t) => trains.add(t));
  Object.keys(status.focusState ?? {}).forEach((t) => trains.add(t));
  return Array.from(trains).sort();
}

/** Filter wheels / cameras / caps are flat top-level keys named after their INDI device name. */
export function getDevices(status: StatusSnapshot): DeviceInfo[] {
  return Object.entries(status)
    .filter(([key]) => !KNOWN_STATUS_KEYS.has(key))
    .map(([name, value]) => ({ name, ...(value as object) }) as DeviceInfo);
}
