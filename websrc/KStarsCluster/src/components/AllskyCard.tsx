import { useEffect, useState } from 'react';
import { fetchAllskyLatest, fetchAllskyChart, allskyImageUrl, type AllskyLatest, type AllskyPoint } from '../api/allskyApi';
import { AllskyChart } from './AllskyChart';

const LATEST_POLL_MS = 60_000;
const CHART_POLL_MS = 5 * 60_000;
const CHART_LIMIT_S = 15_000;

interface Props {
  cam: string;
  label: string;
  /** false for a camera pointed at the dome interior rather than the sky — the star count is
   * meaningless there, so this only skips that overlay. The history chart still fetches and
   * renders regardless (its hover-to-preview is useful for browsing past captures on any
   * camera, dome-facing or not — only its "stars" trace happens to read as flat/noise there). */
  showDetails: boolean;
}

/** Independent cross-check against the weather station — cloud cover drops star count sharply,
 * so this is a quick "is the sky actually clear right now" glance. */
export function AllskyCard({ cam, label, showDetails }: Props) {
  const [latest, setLatest] = useState<AllskyLatest | null>(null);
  const [points, setPoints] = useState<AllskyPoint[]>([]);
  const [error, setError] = useState(false);
  // Set while hovering a point on the star-count chart — overrides the card's background photo
  // with whatever was captured at that point in history, reverting to the latest capture on
  // mouse-leave (see AllskyChart's onHover).
  const [hoveredUrl, setHoveredUrl] = useState<string | undefined>(undefined);
  // The background actually shown — only updated once the corresponding image has finished
  // loading, so swapping (hovering a new point, or the periodic "latest" poll landing) never
  // flashes the card's black background-color while the new one downloads.
  const [displayedImgUrl, setDisplayedImgUrl] = useState<string | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;

    function pollLatest() {
      fetchAllskyLatest(cam)
        .then((data) => { if (!cancelled) { setLatest(data); setError(false); } })
        .catch(() => { if (!cancelled) setError(true); });
    }

    pollLatest();
    const interval = setInterval(pollLatest, LATEST_POLL_MS);
    return () => { cancelled = true; clearInterval(interval); };
  }, [cam]);

  useEffect(() => {
    let cancelled = false;

    function pollChart() {
      fetchAllskyChart(cam, CHART_LIMIT_S)
        .then((data) => { if (!cancelled) setPoints(data); })
        .catch(() => { /* chart is a nice-to-have — leave whatever was showing */ });
    }

    pollChart();
    const interval = setInterval(pollChart, CHART_POLL_MS);
    return () => { cancelled = true; clearInterval(interval); };
  }, [cam]);

  const backgroundPath = hoveredUrl ?? latest?.url;
  const imgUrl = backgroundPath ? allskyImageUrl(cam, backgroundPath) : undefined;

  useEffect(() => {
    if (!imgUrl) {
      setDisplayedImgUrl(undefined);
      return undefined;
    }

    let cancelled = false;
    const img = new Image();
    img.onload = () => { if (!cancelled) setDisplayedImgUrl(imgUrl); };
    img.src = imgUrl;
    return () => { cancelled = true; };
  }, [imgUrl]);

  return (
    <div className="card allsky-card" style={displayedImgUrl ? { backgroundImage: `url(${displayedImgUrl})` } : undefined}>
      <a href={imgUrl} target="_blank" rel="noreferrer" className="allsky-card-link" aria-label={label}>
        <h3>{label}</h3>
        {error && <div className="hfr-chart-empty">Allsky camera unreachable</div>}
        {showDetails && latest && (
          <span className="allsky-overlay">
            ★ {latest.stars ?? '—'} · {latest.moonmode == null ? 'moon —' : latest.moonmode ? 'moon up' : 'moon down'}
          </span>
        )}
      </a>
      <div className="allsky-chart-wrap">
        <AllskyChart points={points} onHover={setHoveredUrl} />
      </div>
    </div>
  );
}
