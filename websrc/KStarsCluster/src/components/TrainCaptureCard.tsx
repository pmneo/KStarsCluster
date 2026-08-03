import type { CapturedImage, SequenceQueueStatus, ViewerImage } from '../api/types';
import { ImageStrip } from './ImageStrip';
import { CaptureQueue } from './CaptureQueue';

interface Props {
  train: string;
  captureStatus?: string;
  captureRunning?: boolean;
  images: CapturedImage[];
  sequenceQueue?: SequenceQueueStatus;
  onOpenImage: (image: ViewerImage) => void;
}

export function TrainCaptureCard({ train, captureStatus, captureRunning, images, sequenceQueue, onOpenImage }: Props) {
  return (
    <div className="card card--wide">
      <h3>{train} · Capture</h3>
      <dl>
        <dt>Capture</dt>
        <dd>{captureStatus ?? '—'}{captureRunning ? ' (running)' : ''}</dd>
      </dl>
      <CaptureQueue queue={sequenceQueue} />
      <ImageStrip images={images} onOpenImage={onOpenImage} />
    </div>
  );
}
