import { useEffect, useState } from 'react';
import { connectSocket } from '../ws';
import type { StatusSnapshot } from './types';

export function useStatusSocket() {
  const [status, setStatus] = useState<StatusSnapshot | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    // paint immediately, don't wait for the first WS push
    fetch('/cmd/status')
      .then((r) => r.json())
      .then(setStatus)
      .catch(() => {});

    return connectSocket(
      '/status/',
      (data) => {
        try {
          setStatus(JSON.parse(data));
        } catch {
          //ignore malformed frame
        }
      },
      setConnected,
    );
  }, []);

  return { status, connected };
}
