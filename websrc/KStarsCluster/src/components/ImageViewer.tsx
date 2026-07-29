import { useEffect, useRef, useState } from 'react';
import { imageUrl, fetchAutoStretch, DEFAULT_STRETCH, type StretchSettings } from '../api/imageApi';
import type { ViewerImage } from '../api/types';

interface Props {
  image: ViewerImage | null;
  onClose: () => void;
}

/** Same convention as ImageStrip's own full-size link — the thumb endpoint downsizes server-side,
 * this just asks for more than any of our cameras actually produce so the real resolution comes
 * through untouched. */
const VIEWER_MAX_DIM = 8000;
const MIN_ZOOM = 1;
// Large sensors easily need >8x zoom (relative to "fit") just to reach their native 1:1 size in a
// modest-sized viewer, and pixel-peeping stars typically wants to go beyond 1:1 too.
const MAX_ZOOM = 20;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

interface DragState {
  startClientX: number;
  startClientY: number;
  startPanX: number;
  startPanY: number;
}

interface PinchState {
  startDistance: number;
  startZoom: number;
}

function pointerDistance(a: { x: number; y: number }, b: { x: number; y: number }): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/** Full-screen lightbox: zoom (wheel or +/-, drag to pan once zoomed past fit) and the same
 * shadows/midtones/highlights STF controls as ImageStrip's old inline editor, just with room to
 * actually see what they're doing. Self-contained — manages its own stretch/zoom/pan state keyed
 * off `image`, so any card can open it just by handing it a filename; it doesn't need to know
 * where that image came from (a captured-image ring buffer, a timeline segment, ...). */
export function ImageViewer({ image, onClose }: Props) {
  const [stretch, setStretch] = useState<StretchSettings>(DEFAULT_STRETCH);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  // zoom=1 means "fit to viewer", which usually isn't the image's native resolution — fitScale is
  // how much smaller/larger that fit size is than naturalWidth, so the displayed percentage can
  // mean actual pixel scale (100% = 1:1) instead of the fairly meaningless "100% of fit".
  const [fitScale, setFitScale] = useState(1);
  const dragRef = useRef<DragState | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  // Multi-touch: pointer events unify mouse/touch/pen, so tracking every currently-down pointer
  // by id is what lets two simultaneous touches become a pinch gesture instead of two independent
  // (and conflicting) single-finger drags.
  const pointersRef = useRef(new Map<number, { x: number; y: number }>());
  const pinchRef = useRef<PinchState | null>(null);

  useEffect(() => {
    if (!image) return;
    setStretch(DEFAULT_STRETCH);
    setZoom(1);
    setPan({ x: 0, y: 0 });
    setFitScale(1);
    fetchAutoStretch(image.filename, false)
      .then(setStretch)
      .catch(() => { /* leave the default stretch in place, no retry */ });
  }, [image?.filename]);

  useEffect(() => {
    if (!image) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [image, onClose]);

  // Locks the page's own scroll while the viewer is open — otherwise a wheel-to-zoom gesture over
  // the canvas also scrolls the body behind it (see the wheel listener below for why the zoom
  // itself needs a second, independent fix too).
  useEffect(() => {
    if (!image) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previousOverflow; };
  }, [image]);

  // React's onWheel prop registers a *passive* listener (for scroll-performance reasons), so
  // calling preventDefault() from it is silently ignored and the page scrolls right through it
  // regardless. A real addEventListener with { passive: false } is the only way to actually stop
  // that — this is why zoom isn't just a plain JSX onWheel handler.
  useEffect(() => {
    const el = canvasRef.current;
    if (!image || !el) return undefined;

    function onWheel(e: WheelEvent) {
      e.preventDefault();
      setZoom((z) => {
        const newZoom = clamp(z * (e.deltaY < 0 ? 1.15 : 1 / 1.15), MIN_ZOOM, MAX_ZOOM);
        setPan((p) => clampPan(p, newZoom));
        return newZoom;
      });
    }

    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [image]);

  // Recomputed whenever the image finishes loading (its "fit" box is only known once it's laid
  // out) and on resize, since the fit box changes with the viewer's own size.
  useEffect(() => {
    if (!image) return undefined;
    function recomputeFitScale() {
      const img = imgRef.current;
      if (img && img.naturalWidth > 0) {
        setFitScale(img.offsetWidth / img.naturalWidth);
      }
    }
    window.addEventListener('resize', recomputeFitScale);
    return () => window.removeEventListener('resize', recomputeFitScale);
  }, [image]);

  if (!image) return null;

  function updateStretch(patch: Partial<StretchSettings>) {
    setStretch((s) => ({ ...s, ...patch }));
  }

  // object-fit: contain means img.offsetWidth/Height (unaffected by the zoom transform) is the
  // "fit" box size — displayed size at a given zoom is that box scaled by zoom, so pan must never
  // exceed half of however much that exceeds the canvas, or the image can be dragged/left fully off
  // screen with no way back (this was the "zoom out and panning is stuck" bug).
  function computeMaxPan(z: number): { x: number; y: number } {
    const img = imgRef.current;
    const canvas = canvasRef.current;
    if (!img || !canvas) return { x: 0, y: 0 };
    return {
      x: Math.max(0, (img.offsetWidth * z - canvas.offsetWidth) / 2),
      y: Math.max(0, (img.offsetHeight * z - canvas.offsetHeight) / 2),
    };
  }

  function clampPan(p: { x: number; y: number }, z: number): { x: number; y: number } {
    const { x, y } = computeMaxPan(z);
    return { x: clamp(p.x, -x, x), y: clamp(p.y, -y, y) };
  }

  function applyZoom(newZoomRaw: number) {
    const z = clamp(newZoomRaw, MIN_ZOOM, MAX_ZOOM);
    setZoom(z);
    setPan((p) => clampPan(p, z));
  }

  async function applyAuto(strong: boolean) {
    try {
      setStretch(await fetchAutoStretch(image!.filename, strong));
    } catch {
      // leave current settings in place on failure
    }
  }

  function handlePointerDown(e: React.PointerEvent) {
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    pointersRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY });

    if (pointersRef.current.size === 2) {
      // A second finger just landed — that's a pinch starting, not a pan, even if one was
      // already in progress from the first finger alone.
      const [a, b] = Array.from(pointersRef.current.values());
      pinchRef.current = { startDistance: pointerDistance(a, b), startZoom: zoom };
      dragRef.current = null;
    } else if (pointersRef.current.size === 1 && zoom > MIN_ZOOM) {
      dragRef.current = { startClientX: e.clientX, startClientY: e.clientY, startPanX: pan.x, startPanY: pan.y };
    }
  }

  function handlePointerMove(e: React.PointerEvent) {
    if (!pointersRef.current.has(e.pointerId)) return;
    pointersRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY });

    const pinch = pinchRef.current;
    if (pinch && pointersRef.current.size === 2) {
      const [a, b] = Array.from(pointersRef.current.values());
      applyZoom(pinch.startZoom * (pointerDistance(a, b) / pinch.startDistance));
      return;
    }

    const drag = dragRef.current;
    if (!drag) return;
    setPan(clampPan(
      { x: drag.startPanX + (e.clientX - drag.startClientX), y: drag.startPanY + (e.clientY - drag.startClientY) },
      zoom,
    ));
  }

  function handlePointerUp(e: React.PointerEvent) {
    pointersRef.current.delete(e.pointerId);
    if (pointersRef.current.size < 2) {
      pinchRef.current = null;
    }
    if (pointersRef.current.size === 0) {
      dragRef.current = null;
    }
  }

  function resetView() {
    applyZoom(1);
  }

  return (
    <div className="image-viewer-backdrop" onClick={onClose}>
      <div className="image-viewer" onClick={(e) => e.stopPropagation()}>
        <div
          ref={canvasRef}
          className="image-viewer-canvas"
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerLeave={handlePointerUp}
        >
          <img
            ref={imgRef}
            src={imageUrl(image.filename, VIEWER_MAX_DIM, stretch)}
            alt={image.filename}
            draggable={false}
            className="image-viewer-img"
            onLoad={() => {
              // offsetWidth is only meaningful once the browser has actually laid the image out.
              requestAnimationFrame(() => {
                const img = imgRef.current;
                if (img && img.naturalWidth > 0) setFitScale(img.offsetWidth / img.naturalWidth);
              });
            }}
            style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`, cursor: zoom > MIN_ZOOM ? 'grab' : 'default' }}
          />
        </div>

        <div className="image-viewer-panel">
          <button type="button" className="image-viewer-close" onClick={onClose} aria-label="Close">×</button>

          <div className="image-viewer-caption">
            {image.target && <div>{image.target}</div>}
            {(image.filter || image.exposure != null) && (
              <div>{image.filter}{image.filter && image.exposure != null ? ' · ' : ''}{image.exposure != null ? `${image.exposure}s` : ''}</div>
            )}
            <div className="image-viewer-filename" title={image.filename}>{image.filename}</div>
          </div>

          <div className="image-viewer-zoom">
            <div className="image-viewer-zoom-row">
              <button type="button" onClick={() => applyZoom(zoom / 1.3)}>−</button>
              <span>{Math.round(zoom * fitScale * 100)}%</span>
              <button type="button" onClick={() => applyZoom(zoom * 1.3)}>+</button>
              <button type="button" onClick={resetView}>Fit</button>
            </div>
            <div className="image-viewer-zoom-row">
              <button type="button" onClick={() => applyZoom(1 / fitScale)}>1:1</button>
            </div>
          </div>

          <div className="image-viewer-stretch">
            <label>
              Shadows
              <input
                type="range" min={0} max={1} step={0.0005}
                value={stretch.shadows}
                onChange={(e) => updateStretch({ shadows: Number(e.target.value) })}
              />
              <span>{stretch.shadows.toFixed(4)}</span>
            </label>
            <label>
              Midtones
              <input
                type="range" min={0} max={1} step={0.0005}
                value={stretch.midtones}
                onChange={(e) => updateStretch({ midtones: Number(e.target.value) })}
              />
              <span>{stretch.midtones.toFixed(4)}</span>
            </label>
            <label>
              Highlights
              <input
                type="range" min={0} max={1} step={0.0005}
                value={stretch.highlights}
                onChange={(e) => updateStretch({ highlights: Number(e.target.value) })}
              />
              <span>{stretch.highlights.toFixed(4)}</span>
            </label>
            <div className="actions">
              <button type="button" onClick={() => applyAuto(false)}>Auto STF</button>
              <button type="button" onClick={() => applyAuto(true)}>Strong Auto STF</button>
              <button type="button" onClick={() => setStretch(DEFAULT_STRETCH)}>Reset</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
