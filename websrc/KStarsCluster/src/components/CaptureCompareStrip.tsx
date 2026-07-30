import { useEffect, useRef, useState } from 'react';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import { allskyImageUrl, type AllskyMatch } from '../api/allskyApi';
import type { TimelineCaptureSelection, ViewerImage } from '../api/types';

const THUMB_MAX_DIM = 200;

function formatAllskyDelta(deltaMs: number): string {
  const mins = Math.round(Math.abs(deltaMs) / 60_000);
  const label = mins < 1 ? '<1m' : mins < 60 ? `${mins}m` : `${Math.floor(mins / 60)}h ${mins % 60}m`;
  return deltaMs <= 0 ? `${label} before` : `${label} after`;
}

interface Props {
  selection: TimelineCaptureSelection;
  allskyMatches: AllskyMatch[];
  onOpenImage: (image: ViewerImage) => void;
  onClear: () => void;
}

/** Shown directly under the Session Timeline while a capture slice is hovered/pinned (see
 * SessionTimeline's onHoverCapture/onSelectCapture) — the captured frame itself plus the nearest
 * allsky match per camera, so "what did the sky look like around this capture" sits right next to
 * it instead of in a separate card. Click the capture thumb to open it in the full ImageViewer;
 * the allsky ones are just for looking at, no stretch settings apply to a raw allsky JPEG. */
export function CaptureCompareStrip({ selection, allskyMatches, onOpenImage, onClear }: Props) {
  const [stretch, setStretch] = useState<StretchSettings>(DEFAULT_STRETCH);
  const requestedFilenameRef = useRef<string | null>(null);

  useEffect(() => {
    const filename = selection.image.filename;
    if (requestedFilenameRef.current === filename) return;
    requestedFilenameRef.current = filename;
    setStretch(DEFAULT_STRETCH);
    fetchAutoStretch(filename, false)
      .then((s) => { if (requestedFilenameRef.current === filename) setStretch(s); })
      .catch(() => { /* leave the default stretch in place, no retry */ });
  }, [selection.image.filename]);

  return (
    <div className="image-strip image-strip--compare">
      <div className="image-thumb image-thumb--compare">
        <button
          type="button"
          className="image-thumb-open"
          onClick={() => onOpenImage(selection.image)}
          title={selection.image.filename}
        >
          <img src={imageUrl(selection.image.filename, THUMB_MAX_DIM, stretch)} alt={selection.image.filename} />
        </button>
        <span className="image-caption">
          {selection.image.target && <>{selection.image.target} · </>}
          {selection.image.filter} {selection.image.exposure}s
        </span>
      </div>
      {allskyMatches.map((m) => (
        <div className="image-thumb image-thumb--compare" key={m.cam}>
          {m.point.url ? (
            <img src={allskyImageUrl(m.cam, m.point.url)} alt={m.label} />
          ) : (
            <div className="image-thumb-placeholder" />
          )}
          <span className="image-caption">{m.label} · {formatAllskyDelta(m.deltaMs)}</span>
        </div>
      ))}
      <button type="button" className="image-strip-clear" onClick={onClear}>
        × Back to recent
      </button>
    </div>
  );
}
