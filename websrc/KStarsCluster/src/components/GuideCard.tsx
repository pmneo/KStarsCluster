import type { GuideDeltaSample } from '../api/types';
import { GuideChart } from './GuideChart';

interface Props {
  guideStatus: string;
  ditheringActive: boolean;
  guideSigma?: { ra: number; de: number };
  guideDeltaHistory: GuideDeltaSample[];
}

export function GuideCard({ guideStatus, ditheringActive, guideSigma, guideDeltaHistory }: Props) {
  const latest = guideDeltaHistory.length > 0 ? guideDeltaHistory[guideDeltaHistory.length - 1] : undefined;

  return (
    <div className="card card--wide">
      <h3>Guiding</h3>
      <dl>
        <dt>State</dt>
        <dd>{guideStatus}{ditheringActive ? ' (dithering)' : ''}</dd>
        {latest && (
          <>
            <dt>Latest error</dt>
            <dd>RA {latest.ra.toFixed(2)}″ · DEC {latest.de.toFixed(2)}″</dd>
          </>
        )}
        {guideSigma && (
          <>
            <dt>RMS</dt>
            <dd>RA {guideSigma.ra.toFixed(2)}″ · DEC {guideSigma.de.toFixed(2)}″</dd>
          </>
        )}
      </dl>
      <GuideChart samples={guideDeltaHistory} />
    </div>
  );
}
