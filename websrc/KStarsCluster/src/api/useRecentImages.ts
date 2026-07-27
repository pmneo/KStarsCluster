import { useEffect, useState } from 'react';
import type { CapturedImage } from './types';

/** New captures only ever show up via the Capture.captureComplete signal — a plain poll is enough, no dedicated WS needed. */
export function useRecentImages(train: string, intervalMs = 5000) {
  const [images, setImages] = useState<CapturedImage[]>([]);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const res = await fetch(`/cmd/images/list/${encodeURIComponent(train)}`);
        const data: CapturedImage[] = await res.json();
        if (!cancelled) setImages(data);
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

  return images;
}

/** PixInsight ScreenTransferFunction convention — all in [0,1]. midtones=0.5 is neutral (linear). */
export interface StretchSettings {
  shadows: number;
  midtones: number;
  highlights: number;
}

export const DEFAULT_STRETCH: StretchSettings = { shadows: 0, midtones: 0.5, highlights: 1 };

export function imageUrl(filename: string, maxDim: number, stretch: StretchSettings): string {
  const params = new URLSearchParams({
    file: filename,
    maxDim: String(maxDim),
    shadows: String(stretch.shadows),
    midtones: String(stretch.midtones),
    highlights: String(stretch.highlights),
  });
  return `/cmd/images/thumb?${params.toString()}`;
}

export async function fetchAutoStretch(filename: string, strong: boolean): Promise<StretchSettings> {
  const params = new URLSearchParams({ file: filename, strong: String(strong) });
  const res = await fetch(`/cmd/images/autostretch?${params.toString()}`);
  return res.json();
}
