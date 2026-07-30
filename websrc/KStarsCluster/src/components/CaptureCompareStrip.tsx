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
  /** null when nothing's currently hovered/pinned — still rendered (as a plain hint), not
   * unmounted, so this card doesn't change height and shove everything below it up and down every
   * time the mouse enters/leaves the timeline. */
  selection: TimelineCaptureSelection | null;
  allskyMatches: AllskyMatch[];
  onOpenImage: (image: ViewerImage) => void;
  onClear: () => void;
}

/** Shown directly under the Session Timeline, always — while a moment is hovered/pinned (see
 * SessionTimeline's onHoverCapture/onSelectCapture) it's the nearest allsky match per camera, so
 * "what did the sky look like around this moment" sits right next to whatever else is showing
 * instead of in a separate card. `selection.capture` is only set if a capture was actually
 * exposing at that precise instant (see findActiveCaptureAt) — hovering a stretch of the timeline
 * with nothing capturing (e.g. SCHEDULER_IDLE) shows just the allsky comparison, not some other
 * capture from hours away as if it were relevant here. Click the capture thumb to open it in the
 * full ImageViewer; the allsky ones are just for looking at, no stretch settings apply to a raw
 * allsky JPEG. */
export function CaptureCompareStrip({ selection, allskyMatches, onOpenImage, onClear }: Props) {
  const [stretch, setStretch] = useState<StretchSettings>(DEFAULT_STRETCH);
  const requestedFilenameRef = useRef<string | null>(null);
  const capture = selection?.capture;

  useEffect(() => {
    const filename = capture?.image.filename;
    if (!filename || requestedFilenameRef.current === filename) return;
    requestedFilenameRef.current = filename;
    setStretch(DEFAULT_STRETCH);
    fetchAutoStretch(filename, false)
      .then((s) => { if (requestedFilenameRef.current === filename) setStretch(s); })
      .catch(() => { /* leave the default stretch in place, no retry */ });
  }, [capture?.image.filename]);

  if (!selection) {
    return (
      <div className="image-strip image-strip--compare">
        <div className="image-thumb image-thumb--compare">
          <div className="image-thumb-placeholder" />
          <span className="image-caption">Hover or click a row above to compare</span>
        </div>
      </div>
    );
  }

  return (
    <div className="image-strip image-strip--compare">
      {capture ? (
        <div className="image-thumb image-thumb--compare">
          <button
            type="button"
            className="image-thumb-open"
            onClick={() => onOpenImage(capture.image)}
            title={capture.image.filename}
          >
            <img src={imageUrl(capture.image.filename, THUMB_MAX_DIM, stretch)} alt={capture.image.filename} />
          </button>
          <span className="image-caption">
            {capture.image.target && <>{capture.image.target} · </>}
            {capture.image.filter} {capture.image.exposure}s
          </span>
        </div>
      ) : (
        <div className="image-thumb image-thumb--compare">
          <div className="image-thumb-placeholder" />
          <span className="image-caption">No capture at this time</span>
        </div>
      )}
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
