import type { GuideDeltaSample } from '../api/types';
import { GuideChart } from './GuideChart';
import { GuideCrosshair } from './GuideCrosshair';

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
      {/* Left column (status text + time chart) only ever needs to be as wide as the crosshair
       * leaves free — stacking dl and GuideChart into one column beside the crosshair instead of
       * below both lets the crosshair span the row's full height and still sit next to text and
       * chart instead of pushing everything down. */}
      <div className="guide-top-row">
        <div className="guide-left-col">
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
        <GuideCrosshair samples={guideDeltaHistory} sigma={guideSigma} />
      </div>
    </div>
  );
}
