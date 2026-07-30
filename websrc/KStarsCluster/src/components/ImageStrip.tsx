import { useEffect, useRef, useState } from 'react';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import { getFrameNumber, getFrameTypeLabel, type CapturedImage, type ViewerImage } from '../api/types';

const THUMB_MAX_DIM = 200;

interface ThumbProps {
  img: CapturedImage;
  stretch: StretchSettings;
  onVisible: () => void;
  onOpen: () => void;
}

/** Only requests its auto-stretch (and only renders the actual <img>, so nothing is fetched at
 * all) once it's actually scrolled near the viewport — with up to 2000 images in the ring buffer
 * (a full night across several targets/filters), fetching every single one's auto-stretch on
 * mount regardless of visibility was wasteful. */
function Thumb({ img, stretch, onVisible, onOpen }: ThumbProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const frameNumber = getFrameNumber(img.filename);

  useEffect(() => {
    if (visible) return undefined;
    const el = ref.current;
    if (!el) return undefined;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          setVisible(true);
          onVisible();
        }
      },
      { rootMargin: '300px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [visible, onVisible]);

  return (
    <div ref={ref} className="image-thumb">
      {visible ? (
        <button type="button" className="image-thumb-open" onClick={onOpen} title={img.filename}>
          <img src={imageUrl(img.filename, THUMB_MAX_DIM, stretch)} alt={img.filename} loading="lazy" />
        </button>
      ) : (
        <div className="image-thumb-placeholder" />
      )}
      <span className="image-caption">
        {img.target && <>{img.target} · </>}
        {getFrameTypeLabel(img.type)} {img.filter} {img.exposure}s
        {frameNumber !== undefined && <> · #{frameNumber}</>}
        {img.hfr >= 0 && <> · HFR {img.hfr.toFixed(2)}</>}
      </span>
    </div>
  );
}

/** Each thumb shows its own Auto-STF; click one to open it full-size in ImageViewer, which has
 * its own (much roomier) stretch/zoom controls — this used to have its own inline manual-editing
 * panel too, folded into the viewer instead of maintaining two separate stretch UIs. */
export function ImageStrip({ images, onOpenImage }: { images: CapturedImage[]; onOpenImage: (image: ViewerImage) => void }) {
  const [autoByFile, setAutoByFile] = useState<Record<string, StretchSettings>>({});
  // Tracks every filename ever requested, successful or not — a 404 (e.g. a since-deleted file)
  // must never be retried, or it gets re-requested on every status push (roughly once a second)
  // forever.
  const requestedFiles = useRef(new Set<string>());

  function requestAutoStretch(filename: string) {
    if (requestedFiles.current.has(filename)) return;
    requestedFiles.current.add(filename);
    fetchAutoStretch(filename, false)
      .then((stretch) => setAutoByFile((s) => ({ ...s, [filename]: stretch })))
      .catch(() => { /* leave it out of autoByFile — DEFAULT_STRETCH is the fallback, no retry */ });
  }

  if (images.length === 0) {
    return <div className="hfr-chart-empty">No images captured yet</div>;
  }

  return (
    <div className="image-strip">
      {images.map((img) => (
        <Thumb
          key={img.filename}
          img={img}
          stretch={autoByFile[img.filename] ?? DEFAULT_STRETCH}
          onVisible={() => requestAutoStretch(img.filename)}
          onOpen={() => onOpenImage({ filename: img.filename, target: img.target, filter: img.filter, exposure: img.exposure })}
        />
      ))}
    </div>
  );
}
