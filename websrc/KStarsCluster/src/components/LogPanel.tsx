import { useEffect, useRef } from 'react';
import { useLogSocket } from '../api/useLogSocket';

export function LogPanel() {
  const lines = useLogSocket();
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.scrollTo(0, ref.current.scrollHeight);
  }, [lines]);

  return (
    <div className="card log-panel">
      <h3>Live Log</h3>
      <div className="log-lines" ref={ref}>
        {lines.map((line, i) => <div key={i}>{line}</div>)}
      </div>
    </div>
  );
}
