import { useEffect, useRef, useState, type ReactNode } from 'react';

/** How long a deliberate unlock stays armed before it automatically re-locks — long enough to
 * find and press the actual button, short enough that a lock left open by mistake doesn't stay
 * open for the rest of the session. */
const AUTO_RELOCK_MS = 8_000;

interface Props {
  children: ReactNode;
  /** Used in the toggle's title/aria-label, e.g. "cap controls" -> "Unlock cap controls". */
  label: string;
}

/** Wraps a whole card's worth of controls that could disrupt an active session (closing the
 * cap/roof mid-exposure, warming cameras, stopping KStars, ...) behind a single lock icon that
 * must be pressed first — one lock per card guards every button inside it, rather than a lock
 * per individual button/group. Locked controls are inert (no click, no hover affordance) rather
 * than merely disabled, so an accidental tap/click on their usual spot does nothing. */
export function SafetyLock({ children, label }: Props) {
  const [unlocked, setUnlocked] = useState(false);
  const relockTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(relockTimer.current), []);

  const toggle = () => {
    window.clearTimeout(relockTimer.current);
    setUnlocked((wasUnlocked) => {
      const next = !wasUnlocked;
      if (next) {
        relockTimer.current = window.setTimeout(() => setUnlocked(false), AUTO_RELOCK_MS);
      }
      return next;
    });
  };

  return (
    <div className={`safety-lock ${unlocked ? 'safety-lock--unlocked' : 'safety-lock--locked'}`}>
      <button
        type="button"
        className="safety-lock-toggle"
        onClick={toggle}
        title={unlocked ? `Lock ${label}` : `Unlock ${label}`}
        aria-pressed={unlocked}
      >
        {unlocked ? '🔓 Unlocked' : '🔒 Locked'}
      </button>
      {/* Locked state is enforced purely via CSS (pointer-events: none + dimming below), not
       * `inert` — @types/react 18 doesn't type that attribute yet. */}
      <div className="safety-lock-body" aria-disabled={!unlocked}>
        {children}
      </div>
    </div>
  );
}
