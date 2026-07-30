/** PixInsight ScreenTransferFunction convention — all in [0,1]. midtones=0.5 is neutral (linear). */
export interface StretchSettings {
  shadows: number;
  midtones: number;
  highlights: number;
}

export const DEFAULT_STRETCH: StretchSettings = { shadows: 0, midtones: 0.5, highlights: 1 };

export function imageUrl(filename: string, maxDim: number, stretch: StretchSettings): string {
  const params = new URLSearchParams({
    file: filename,
    maxDim: String(maxDim),
    shadows: String(stretch.shadows),
    midtones: String(stretch.midtones),
    highlights: String(stretch.highlights),
  });
  return `/images/thumb?${params.toString()}`;
}

export async function fetchAutoStretch(filename: string, strong: boolean): Promise<StretchSettings> {
  const params = new URLSearchParams({ file: filename, strong: String(strong) });
  const res = await fetch(`/images/autostretch?${params.toString()}`);
  return res.json();
}
