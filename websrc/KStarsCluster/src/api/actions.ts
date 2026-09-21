import type { SchedulerJob } from './types';

export async function runAction(path: string, params?: Record<string, string>): Promise<unknown> {
  const url = params ? `/cmd/${path}?${new URLSearchParams(params)}` : `/cmd/${path}`;
  const res = await fetch(url, params ? { method: 'POST' } : undefined);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** Parses the .esl file KStarsClusterServer is configured to load (see its own loadSchedule
 * field) straight off disk, rather than asking a running Ekos scheduler — used by the Sky Map's
 * "open targets" overlay when Ekos isn't up to ask live (see StatusSnapshot.ekosReady). A
 * freshly-parsed job has no run history, so every job comes back state 0 (JOB_IDLE). */
export async function fetchScheduleFileJobs(): Promise<SchedulerJob[]> {
  const jobs = await runAction('scheduleFile');
  return Array.isArray(jobs) ? (jobs as SchedulerJob[]) : [];
}

export const actions = {
  connection: {
    startEkos: () => runAction('startEkos'),
    stopKStars: () => runAction('stopKStars'),
    suspend: () => runAction('suspend'),
    resume: () => runAction('resume'),
  },
  cooling: {
    preCool: () => runAction('preCool'),
    warmCameras: () => runAction('warmCameras'),
  },
  observatory: {
    roofOpen: () => runAction('roof/unpark'),
    roofClose: () => runAction('roof/park'),
    capOpen: () => runAction('cap/open'),
    capClose: () => runAction('cap/close'),
    lightOn: () => runAction('light/on'),
    lightOff: () => runAction('light/off'),
  },
  calibration: {
    autoFlat: (angles: number[]) => runAction(`flats/${angles.join(',')}`),
  },
  scheduler: {
    start: () => runAction('scheduler/start'),
    stop: () => runAction('scheduler/stop'),
    /** Ekos has no D-Bus signal for "a job was added/edited/reordered" in the Scheduler (only
     * jobStarted/jobEnded), so the live jobs list otherwise only catches up once some job
     * actually starts or ends — see KStarsCluster's "scheduler/refresh" action. */
    refresh: () => runAction('scheduler/refresh'),
    /** Appends one new lead(Primary)+follower(Secondary) job pair to the configured .esl file
     * (and, if Ekos is connected, also live-appends it to the running Scheduler's queue) — see
     * KStarsCluster's "scheduler/addJob" action for the field shapes/defaults. This observatory
     * is always a dual-train rig (every hand-created pair in a real schedule targets both
     * trains), so there's no per-job train choice here. `ra`/`dec`/`pa` are the target's J2000 RA
     * (hours), J2000 Dec (degrees), and position angle (degrees) — typically whatever the Sky
     * Map's Planning FOV "Add job here" button captured. `insertBeforeJobName` is an existing
     * lead job's name to insert the new pair before, or omitted to append at the end. */
    addJob: (job: {
      name: string; ra: number; dec: number; pa: number;
      sequence: string; repeats: number; insertBeforeJobName?: string;
    }) => runAction('scheduler/addJob', {
      name: job.name,
      ra: String(job.ra),
      dec: String(job.dec),
      pa: String(job.pa),
      sequence: job.sequence,
      repeats: String(job.repeats),
      ...(job.insertBeforeJobName ? { insertBeforeJobName: job.insertBeforeJobName } : {}),
    }),
  },
  train: {
    focusRun: (train: string) => runAction(`focus/run/${encodeURIComponent(train)}`),
    focusAbort: (train: string) => runAction(`focus/abort/${encodeURIComponent(train)}`),
    captureAbort: (train: string) => runAction(`capture/abort/${encodeURIComponent(train)}`),
  },
};
