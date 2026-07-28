import { useEffect, useRef, useState } from 'react';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import { getFrameTypeLabel, type CapturedImage } from '../api/types';

const THUMB_MAX_DIM = 200;
const FULL_MAX_DIM = 8000;

interface ThumbProps {
  img: CapturedImage;
  stretch: StretchSettings;
  isEditing: boolean;
  onVisible: () => void;
  onEditToggle: () => void;
}

/** Only requests its auto-stretch (and only renders the actual <img>, so nothing is fetched at
 * all) once it's actually scrolled near the viewport — with up to 50 images in the ring buffer,
 * fetching every single one's auto-stretch on mount regardless of visibility was wasteful. */
function Thumb({ img, stretch, isEditing, onVisible, onEditToggle }: ThumbProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);

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

  function handleEditClick() {
    if (!visible) {
      setVisible(true);
      onVisible();
    }
    onEditToggle();
  }

  return (
    <div ref={ref} className={isEditing ? 'image-thumb image-thumb--editing' : 'image-thumb'}>
      {visible ? (
        <a href={imageUrl(img.filename, FULL_MAX_DIM, stretch)} target="_blank" rel="noreferrer" title={img.filename}>
          <img src={imageUrl(img.filename, THUMB_MAX_DIM, stretch)} alt={img.filename} loading="lazy" />
        </a>
      ) : (
        <div className="image-thumb-placeholder" />
      )}
      <span className="image-caption">
        {getFrameTypeLabel(img.type)} {img.filter} {img.exposure}s
        {img.hfr >= 0 && <> · HFR {img.hfr.toFixed(2)}</>}
      </span>
      <button type="button" className="stf-toggle" onClick={handleEditClick}>
        {isEditing ? 'Editing…' : 'Manual'}
      </button>
    </div>
  );
}

/** Each image defaults to its own Auto-STF; click "Manual" on a thumb to fine-tune that one image. */
export function ImageStrip({ images }: { images: CapturedImage[] }) {
  const [autoByFile, setAutoByFile] = useState<Record<string, StretchSettings>>({});
  const [manualByFile, setManualByFile] = useState<Record<string, StretchSettings>>({});
  const [editing, setEditing] = useState<string | null>(null);
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

  function stretchFor(filename: string): StretchSettings {
    return manualByFile[filename] ?? autoByFile[filename] ?? DEFAULT_STRETCH;
  }

  function updateManual(filename: string, patch: Partial<StretchSettings>) {
    setManualByFile((m) => ({ ...m, [filename]: { ...stretchFor(filename), ...patch } }));
  }

  async function applyAutoStretch(filename: string, strong: boolean) {
    try {
      const stretch = await fetchAutoStretch(filename, strong);
      setManualByFile((m) => ({ ...m, [filename]: stretch }));
    } catch {
      // leave current settings in place on failure
    }
  }

  function resetManual(filename: string) {
    setManualByFile((m) => {
      const next = { ...m };
      delete next[filename];
      return next;
    });
  }

  if (images.length === 0) {
    return <div className="hfr-chart-empty">No images captured yet</div>;
  }

  const editingImage = editing ? images.find((i) => i.filename === editing) : undefined;

  return (
    <div>
      <div className="image-strip">
        {images.map((img) => (
          <Thumb
            key={img.filename}
            img={img}
            stretch={stretchFor(img.filename)}
            isEditing={img.filename === editing}
            onVisible={() => requestAutoStretch(img.filename)}
            onEditToggle={() => setEditing(img.filename === editing ? null : img.filename)}
          />
        ))}
      </div>

      {editingImage && (
        <div className="stretch-controls">
          <span className="image-caption" title={editingImage.filename}>{editingImage.filename}</span>
          <label>
            Shadows
            <input
              type="number" step="0.00001" min={0} max={1}
              value={stretchFor(editingImage.filename).shadows}
              onChange={(e) => updateManual(editingImage.filename, { shadows: Number(e.target.value) })}
            />
          </label>
          <label>
            Midtones
            <input
              type="number" step="0.00001" min={0} max={1}
              value={stretchFor(editingImage.filename).midtones}
              onChange={(e) => updateManual(editingImage.filename, { midtones: Number(e.target.value) })}
            />
          </label>
          <label>
            Highlights
            <input
              type="number" step="0.00001" min={0} max={1}
              value={stretchFor(editingImage.filename).highlights}
              onChange={(e) => updateManual(editingImage.filename, { highlights: Number(e.target.value) })}
            />
          </label>
          <button type="button" onClick={() => applyAutoStretch(editingImage.filename, false)}>Auto STF</button>
          <button type="button" onClick={() => applyAutoStretch(editingImage.filename, true)}>Strong Auto STF</button>
          <button type="button" onClick={() => resetManual(editingImage.filename)}>Reset</button>
        </div>
      )}
    </div>
  );
}
