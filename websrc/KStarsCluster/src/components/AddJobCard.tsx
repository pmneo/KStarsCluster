import { useEffect, useState } from 'react';
import { actions, fetchScheduleFileJobs, fetchSequenceFiles, type SequenceFileInfo } from '../api/actions';
import type { SchedulerJob } from '../api/types';

export interface CapturedFov {
  ra: number;
  dec: number;
  widthArcmin: number;
  heightArcmin: number;
  rotationDeg: number;
}

interface Props {
  /** Set by the Sky Map's Planning FOV "Add job here" button (see App.tsx's onUsePlanningFov) —
   * the target/rotation this form submits with. The dialog only renders while this is non-null;
   * null (the initial state, and after a successful/cancelled submit) means "closed". */
  capturedFov: CapturedFov | null;
  onFovConsumed: () => void;
}

const INSERT_AT_END = '';

/** e.g. 5400 -> "1h30m", 3240 -> "54m" — matches the compact style already used elsewhere for
 * durations in this app. */
function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.round((totalSeconds % 3600) / 60);
  return h > 0 ? `${h}h${String(m).padStart(2, '0')}m` : `${m}m`;
}

/** A popup dialog (same backdrop-click-to-close / Escape-to-close convention as ImageViewer),
 * not a plain grid card — an earlier version rendered inline in the dashboard grid, which meant
 * clicking the Sky Map's "Add job here" button silently updated a card the user had to go
 * scrolling for, with zero feedback at the click site itself. */
export function AddJobCard({ capturedFov, onFovConsumed }: Props) {
  const [knownJobs, setKnownJobs] = useState<SchedulerJob[]>([]);
  const [sequenceFiles, setSequenceFiles] = useState<SequenceFileInfo[]>([]);
  const [name, setName] = useState('');
  const [sequence, setSequence] = useState('');
  const [repeats, setRepeats] = useState(1);
  const [insertBeforeJobName, setInsertBeforeJobName] = useState(INSERT_AT_END);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The existing-job ordering (for "insert before") comes from whatever's already in the
  // configured schedule file (works whether or not Ekos is running, same source SchedulerCard's
  // planned-mode fallback and the Sky Map's "open targets" overlay already use) — not the live
  // jobs list, since opticalTrain/lead are only ever populated by parseEslFile(), never by Ekos's
  // own currentJobJson/jsonJobs (see SchedulerJob.java's field comments). Sequence options come
  // from every .esq file sitting in the same folder(s) as those jobs' sequences, not just the
  // ones already in use (see fetchSequenceFiles/KStarsClusterServer's "sequenceFiles" action).
  // Both refetched every time the dialog opens, not cached — either can change between one "Add
  // job here" click and the next.
  useEffect(() => {
    if (!capturedFov) return;
    fetchScheduleFileJobs().then(setKnownJobs).catch(() => {});
    fetchSequenceFiles().then(setSequenceFiles).catch(() => {});
  }, [capturedFov]);

  useEffect(() => {
    if (!capturedFov) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onFovConsumed();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [capturedFov, onFovConsumed]);

  useEffect(() => {
    if (!capturedFov) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previousOverflow; };
  }, [capturedFov]);

  // Reset the form fresh each time the dialog opens, rather than carrying over whatever was
  // typed for a previous target.
  useEffect(() => {
    if (!capturedFov) return;
    setName('');
    setRepeats(1);
    setInsertBeforeJobName(INSERT_AT_END);
    setError(null);
  }, [capturedFov]);

  const sequenceOptions = sequenceFiles.slice().sort((a, b) => a.name.localeCompare(b.name));
  // Lead jobs only — a follower has no name of its own (see SchedulerJob.parseEslFile's
  // inheritance comment) and inserting "before" one would split an existing pair in half, which
  // the backend already refuses to do (see SchedulerJob.findInsertionPoint). Kept in file order
  // (parseEslFile preserves document order) so this reads the same top-to-bottom as the schedule
  // itself.
  const existingLeadJobNames = knownJobs.filter((j) => j.lead).map((j) => j.name);

  useEffect(() => {
    if (!sequence && sequenceOptions.length > 0) setSequence(sequenceOptions[0].path);
  }, [sequenceOptions, sequence]);

  if (!capturedFov) return null;

  async function submit() {
    if (!capturedFov || !name.trim() || !sequence) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await actions.scheduler.addJob({
        name: name.trim(),
        ra: capturedFov.ra,
        dec: capturedFov.dec,
        pa: capturedFov.rotationDeg,
        sequence,
        repeats,
        insertBeforeJobName: insertBeforeJobName || undefined,
      });
      if (res !== 'OK') {
        setError(typeof res === 'string' ? res : 'Failed to add job');
        return;
      }
      onFovConsumed();
    }
    catch {
      setError('Failed to add job');
    }
    finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="add-job-backdrop" onClick={onFovConsumed}>
      <div className="add-job-dialog" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="add-job-close" onClick={onFovConsumed} aria-label="Close">×</button>
        <h3>Add Scheduler Job</h3>
        <dl>
          <dt>Target</dt>
          <dd>{capturedFov.ra.toFixed(4)}h / {capturedFov.dec.toFixed(4)}°</dd>
          <dt>Rotation</dt>
          <dd>{capturedFov.rotationDeg.toFixed(1)}°</dd>
          <dt>FOV</dt>
          <dd>{capturedFov.widthArcmin.toFixed(1)}&apos; × {capturedFov.heightArcmin.toFixed(1)}&apos;</dd>
        </dl>
        <p className="muted-note">Creates both a Primary (lead) and Secondary (follower) job for this target.</p>
        <div className="add-job-form">
          <label>
            Name
            <input autoFocus value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label>
            Sequence
            <select value={sequence} onChange={(e) => setSequence(e.target.value)}>
              {sequenceOptions.length === 0 && <option value="">No known sequences</option>}
              {sequenceOptions.map((s) => (
                <option key={s.path} value={s.path}>{s.name} ({formatDuration(s.seconds)})</option>
              ))}
            </select>
          </label>
          <label>
            Repeats
            <input
              type="number" min={1} step={1} value={repeats}
              onChange={(e) => setRepeats(Number(e.target.value))}
            />
          </label>
          <label>
            Insert before
            <select value={insertBeforeJobName} onChange={(e) => setInsertBeforeJobName(e.target.value)}>
              <option value={INSERT_AT_END}>(at the end)</option>
              {existingLeadJobNames.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
          <button onClick={submit} disabled={submitting || !name.trim() || !sequence}>
            {submitting ? 'Adding…' : 'Add Job'}
          </button>
          <button onClick={onFovConsumed} disabled={submitting}>Cancel</button>
        </div>
        {error && <p className="muted-note add-job-error">{error}</p>}
      </div>
    </div>
  );
}
