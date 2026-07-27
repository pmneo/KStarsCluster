import { useEffect, useRef, useState } from 'react';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import { getFrameTypeLabel, type CapturedImage } from '../api/types';

const THUMB_MAX_DIM = 200;
const FULL_MAX_DIM = 8000;

/** Each image defaults to its own Auto-STF; click "Manual" on a thumb to fine-tune that one image. */
export function ImageStrip({ images }: { images: CapturedImage[] }) {
  const [autoByFile, setAutoByFile] = useState<Record<string, StretchSettings>>({});
  const [manualByFile, setManualByFile] = useState<Record<string, StretchSettings>>({});
  const [editing, setEditing] = useState<string | null>(null);
  const fetchedFiles = useRef(new Set<string>());

  useEffect(() => {
    for (const img of images) {
      if (fetchedFiles.current.has(img.filename)) continue;
      fetchedFiles.current.add(img.filename);
      fetchAutoStretch(img.filename, false)
        .then((stretch) => setAutoByFile((s) => ({ ...s, [img.filename]: stretch })))
        .catch(() => fetchedFiles.current.delete(img.filename));
    }
  }, [images]);

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
        {images.map((img) => {
          const stretch = stretchFor(img.filename);
          const isEditing = img.filename === editing;
          return (
            <div key={img.filename} className={isEditing ? 'image-thumb image-thumb--editing' : 'image-thumb'}>
              <a href={imageUrl(img.filename, FULL_MAX_DIM, stretch)} target="_blank" rel="noreferrer" title={img.filename}>
                <img src={imageUrl(img.filename, THUMB_MAX_DIM, stretch)} alt={img.filename} loading="lazy" />
              </a>
              <span className="image-caption">
                {getFrameTypeLabel(img.type)} {img.filter} {img.exposure}s
                {img.hfr >= 0 && <> · HFR {img.hfr.toFixed(2)}</>}
              </span>
              <button type="button" className="stf-toggle" onClick={() => setEditing(isEditing ? null : img.filename)}>
                {isEditing ? 'Editing…' : 'Manual'}
              </button>
            </div>
          );
        })}
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
