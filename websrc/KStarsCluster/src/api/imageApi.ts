// Canonical definitions now live in skymap-widget (SkyMapCard's own "last image" overlay needs
// them too) — re-exported here so this dashboard's other consumers (ImageStrip, ImageViewer,
// CaptureCompareStrip) don't need to know that.
export type { StretchSettings } from 'skymap-widget';
export { DEFAULT_STRETCH, imageUrl, fetchAutoStretch } from 'skymap-widget';
