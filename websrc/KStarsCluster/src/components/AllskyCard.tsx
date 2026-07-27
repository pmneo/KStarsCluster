import { useEffect, useState } from 'react';
import { fetchAllskyLatest, fetchAllskyChart, allskyImageUrl, type AllskyLatest, type AllskyPoint } from '../api/allskyApi';
import { AllskyChart } from './AllskyChart';

const LATEST_POLL_MS = 60_000;
const CHART_POLL_MS = 5 * 60_000;
const CHART_LIMIT_S = 15_000;

/** Independent cross-check against the weather station — cloud cover drops star count and
 * shifts SQM sharply, so this is a quick "is the sky actually clear right now" glance. */
export function AllskyCard() {
  const [latest, setLatest] = useState<AllskyLatest | null>(null);
  const [points, setPoints] = useState<AllskyPoint[]>([]);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;

    function pollLatest() {
      fetchAllskyLatest()
        .then((data) => { if (!cancelled) { setLatest(data); setError(false); } })
        .catch(() => { if (!cancelled) setError(true); });
    }

    pollLatest();
    const interval = setInterval(pollLatest, LATEST_POLL_MS);
    return () => { cancelled = true; clearInterval(interval); };
  }, []);

  useEffect(() => {
    let cancelled = false;

    function pollChart() {
      fetchAllskyChart(CHART_LIMIT_S)
        .then((data) => { if (!cancelled) setPoints(data); })
        .catch(() => { /* chart is a nice-to-have — leave whatever was showing */ });
    }

    pollChart();
    const interval = setInterval(pollChart, CHART_POLL_MS);
    return () => { cancelled = true; clearInterval(interval); };
  }, []);

  return (
    <div className="card card--wide">
      <h3>Allsky</h3>
      {error && <div className="hfr-chart-empty">Allsky camera unreachable</div>}
      {latest && (
        <dl>
          <dt>Stars</dt>
          <dd>
            {latest.stars ?? '—'}
            {latest.starsAvg != null && <> (avg {latest.starsAvg.toFixed(1)})</>}
          </dd>
          <dt>SQM</dt>
          <dd>{latest.jsqm != null ? latest.jsqm.toFixed(1) : '—'}</dd>
          <dt>Moon</dt>
          <dd>{latest.moonmode ? 'up' : 'down'}</dd>
        </dl>
      )}
      {latest?.url && (
        <a href={allskyImageUrl(latest.url)} target="_blank" rel="noreferrer" className="allsky-image-link">
          <img src={allskyImageUrl(latest.url)} alt="Latest allsky capture" className="allsky-image" loading="lazy" />
        </a>
      )}
      <AllskyChart points={points} />
    </div>
  );
}
