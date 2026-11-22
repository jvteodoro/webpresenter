/** Where background music comes from; null where optional means "inherit". */
export interface BackgroundSource {
  kind: 'PLAYLIST' | 'MEDIA' | 'SILENCE';
  ref?: string | null;
}

/** A source with its display name ("Playlist Recepção", a track title, "Silêncio"). */
export interface BackgroundView {
  source: BackgroundSource;
  name: string;
}

export interface AudioSettings {
  master: number;
  media: number;
  background: number;
  effects: number;
  fadeInMs: number;
  fadeOutMs: number;
  duckPercent: number;
  defaultBackground: BackgroundSource | null;
  defaultBackgroundName?: string;
}

export interface MixerLevels {
  master: number;
  media: number;
  background: number;
  effects: number;
}
