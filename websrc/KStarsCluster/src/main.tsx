import { createRoot } from 'react-dom/client';
import { App } from './App';
import { SkyMapCardExperiment } from './components/SkyMapCardExperiment';
import './index.css';

// Reached via ?experiment=skymap — a throwaway harness for testing the zenith-lock rotation
// experiment (see SkyMapCardExperiment's own notes) against the live app's actual API endpoints
// (footprints, observatory info, ...) without touching the real dashboard or its layout.
function ExperimentHarness() {
  return (
    <div style={{ padding: 24, maxWidth: 900 }}>
      <SkyMapCardExperiment activeJob={null} />
    </div>
  );
}

const isExperiment = new URLSearchParams(window.location.search).get('experiment') === 'skymap';
createRoot(document.getElementById('root')!).render(isExperiment ? <ExperimentHarness /> : <App />);
