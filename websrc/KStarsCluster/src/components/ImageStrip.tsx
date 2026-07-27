import { useState } from 'react';
import { useRecentImages, imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/useRecentImages';
import { getFrameTypeLabel } from '../api/types';

const THUMB_MAX_DIM = 200;
const FULL_MAX_DIM = 8000;

export function ImageStrip({ train }: { train: string }) {
  const images = useRecentImages(train);
  const [stretch, setStretch] = useState<StretchSettings>(DEFAULT_STRETCH);

  async function applyAutoStretch(strong: boolean) {
    if (images.length === 0) return;
    try {
      setStretch(await fetchAutoStretch(images[0].filename, strong));
    } catch {
      //leave current settings in place on failure
    }
  }

  return (
    <div>
      <div className="stretch-controls">
        <label>
          Shadows
          <input
            type="number" step="0.00001" min={0} max={1}
            value={stretch.shadows}
            onChange={(e) => setStretch((s) => ({ ...s, shadows: Number(e.target.value) }))}
          />
        </label>
        <label>
          Midtones
          <input
            type="number" step="0.00001" min={0} max={1}
            value={stretch.midtones}
            onChange={(e) => setStretch((s) => ({ ...s, midtones: Number(e.target.value) }))}
          />
        </label>
        <label>
          Highlights
          <input
            type="number" step="0.00001" min={0} max={1}
            value={stretch.highlights}
            onChange={(e) => setStretch((s) => ({ ...s, highlights: Number(e.target.value) }))}
          />
        </label>
        <button type="button" onClick={() => applyAutoStretch(false)} disabled={images.length === 0}>Auto STF</button>
        <button type="button" onClick={() => applyAutoStretch(true)} disabled={images.length === 0}>Strong Auto STF</button>
        <button type="button" onClick={() => setStretch(DEFAULT_STRETCH)}>Reset</button>
      </div>

      {images.length === 0 ? (
        <div className="hfr-chart-empty">No images captured yet</div>
      ) : (
        <div className="image-strip">
          {images.map((img) => (
            <a
              key={img.filename}
              href={imageUrl(img.filename, FULL_MAX_DIM, stretch)}
              target="_blank"
              rel="noreferrer"
              className="image-thumb"
              title={img.filename}
            >
              <img src={imageUrl(img.filename, THUMB_MAX_DIM, stretch)} alt={img.filename} loading="lazy" />
              <span className="image-caption">
                {getFrameTypeLabel(img.type)} {img.filter} {img.exposure}s
                {img.hfr >= 0 && <> · HFR {img.hfr.toFixed(2)}</>}
              </span>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
