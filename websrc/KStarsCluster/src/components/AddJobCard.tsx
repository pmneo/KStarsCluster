import { useEffect, useState } from 'react';
import { actions, fetchScheduleFileJobs } from '../api/actions';
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
   * the target/rotation this form submits with. Null until that button has been clicked at least
   * once (or after a successful/cancelled submit, see onFovConsumed). */
  capturedFov: CapturedFov | null;
  onFovConsumed: () => void;
}

const INSERT_AT_END = '';

/** file:// URI (see SchedulerJob.java's `sequence` field) -> plain filesystem path, matching what
 * the .esl <Sequence> tag actually needs (confirmed against a real schedule file — plain absolute
 * paths, not URIs). */
function sequencePathFromUri(uri: string): string {
  try {
    return decodeURIComponent(new URL(uri).pathname);
  } catch {
    return uri;
  }
}

export function AddJobCard({ capturedFov, onFovConsumed }: Props) {
  const [knownJobs, setKnownJobs] = useState<SchedulerJob[]>([]);
  const [name, setName] = useState('');
  const [sequence, setSequence] = useState('');
  const [repeats, setRepeats] = useState(1);
  const [insertBeforeJobName, setInsertBeforeJobName] = useState(INSERT_AT_END);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  // Sequence options + the existing-job ordering (for "insert before") both come from whatever's
  // already in the configured schedule file (works whether or not Ekos is running, same source
  // SchedulerCard's planned-mode fallback and the Sky Map's "open targets" overlay already use) —
  // not the live jobs list, since a readable sequence path is only ever populated by
  // parseEslFile(), never by Ekos's own currentJobJson/jsonJobs (see SchedulerJob.java's field
  // comments).
  useEffect(() => {
    fetchScheduleFileJobs().then(setKnownJobs).catch(() => {});
  }, []);

  const sequenceOptions = Array.from(new Set(
    knownJobs.map((j) => j.sequence).filter((s): s is string => !!s).map(sequencePathFromUri),
  )).sort();
  // Lead jobs only — a follower has no name of its own (see SchedulerJob.parseEslFile's
  // inheritance comment) and inserting "before" one would split an existing pair in half, which
  // the backend already refuses to do (see SchedulerJob.findInsertionPoint). Kept in file order
  // (parseEslFile preserves document order) so this reads the same top-to-bottom as the schedule
  // itself.
  const existingLeadJobNames = knownJobs.filter((j) => j.lead).map((j) => j.name);

  useEffect(() => {
    if (!sequence && sequenceOptions.length > 0) setSequence(sequenceOptions[0]);
  }, [sequenceOptions, sequence]);

  if (!capturedFov) {
    return (
      <div className="card">
        <h3>Add Scheduler Job</h3>
        <p className="muted-note">
          Use the &quot;Add job here&quot; button on the Sky Map&apos;s Planning FOV panel to pick a target first.
        </p>
      </div>
    );
  }

  async function submit() {
    if (!capturedFov || !name.trim() || !sequence) return;
    setSubmitting(true);
    setResult(null);
    try {
      await actions.scheduler.addJob({
        name: name.trim(),
        ra: capturedFov.ra,
        dec: capturedFov.dec,
        pa: capturedFov.rotationDeg,
        sequence,
        repeats,
        insertBeforeJobName: insertBeforeJobName || undefined,
      });
      setResult(`Added "${name.trim()}" (Primary + Secondary)`);
      setName('');
      onFovConsumed();
    }
    catch {
      setResult('Failed to add job');
    }
    finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card">
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
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          Sequence
          <select value={sequence} onChange={(e) => setSequence(e.target.value)}>
            {sequenceOptions.length === 0 && <option value="">No known sequences</option>}
            {sequenceOptions.map((s) => <option key={s} value={s}>{s}</option>)}
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
      {result && <p className="muted-note">{result}</p>}
    </div>
  );
}
