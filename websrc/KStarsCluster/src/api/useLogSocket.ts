import { useEffect, useState } from 'react';
import { connectSocket } from '../ws';

const MAX_LINES = 500;

export function useLogSocket() {
  const [lines, setLines] = useState<string[]>([]);

  useEffect(() => {
    return connectSocket('/logging/', (data) => {
      setLines((prev) => {
        const next = [...prev, data];
        return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
      });
    });
  }, []);

  return lines;
}
