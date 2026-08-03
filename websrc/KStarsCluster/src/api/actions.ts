import type { SchedulerJob } from './types';

export async function runAction(path: string): Promise<unknown> {
  const res = await fetch(`/cmd/${path}`);
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
  },
  train: {
    focusRun: (train: string) => runAction(`focus/run/${encodeURIComponent(train)}`),
    focusAbort: (train: string) => runAction(`focus/abort/${encodeURIComponent(train)}`),
    captureAbort: (train: string) => runAction(`capture/abort/${encodeURIComponent(train)}`),
  },
};
