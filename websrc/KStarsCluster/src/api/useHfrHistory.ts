import { useEffect, useState } from 'react';
import type { HfrSample } from './types';

/** HFR samples only ever grow between autofocus runs, so a plain poll is enough — no need for a dedicated WS. */
export function useHfrHistory(train: string, intervalMs = 5000) {
  const [samples, setSamples] = useState<HfrSample[]>([]);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const res = await fetch(`/cmd/hfr/${encodeURIComponent(train)}`);
        const data: HfrSample[] = await res.json();
        if (!cancelled) setSamples(data);
      } catch {
        //transient fetch failure — next poll will retry
      }
    }

    poll();
    const id = setInterval(poll, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [train, intervalMs]);

  return samples;
}
