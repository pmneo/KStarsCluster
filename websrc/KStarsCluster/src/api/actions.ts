export async function runAction(path: string): Promise<unknown> {
  const res = await fetch(`/cmd/${path}`);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
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
