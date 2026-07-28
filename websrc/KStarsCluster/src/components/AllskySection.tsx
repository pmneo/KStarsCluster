import { useEffect, useState } from 'react';
import { fetchAllskyCameras, type AllskyCameraInfo } from '../api/allskyApi';
import { AllskyCard } from './AllskyCard';

/** One AllskyCard per configured indi-allsky camera — the frontend never hardcodes which
 * cameras exist, where, or whether they show sky detail, that's entirely a backend
 * (KStarsCluster) concern. */
export function AllskySection() {
  const [cameras, setCameras] = useState<Record<string, AllskyCameraInfo>>({});

  useEffect(() => {
    fetchAllskyCameras().then(setCameras).catch(() => {});
  }, []);

  return (
    <>
      {Object.entries(cameras).map(([cam, info]) => (
        <AllskyCard key={cam} cam={cam} label={info.label} showDetails={info.showDetails} />
      ))}
    </>
  );
}
