export type MediaKind = 'VIDEO' | 'AUDIO' | 'IMAGE';

/** What a library file is for; the library shows each purpose in its own section. */
export type MediaPurpose = 'GENERAL' | 'SONG_AUDIO' | 'SONG_BACKGROUND' | 'BACKGROUND_MUSIC' | 'SOUND_EFFECT' | 'COVER' | 'MISC' | 'MUSICAL_MESSAGE';

export interface MediaAsset {
  id: string;
  title: string;
  originalFilename: string;
  kind: MediaKind;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  purpose: MediaPurpose;
  createdAt: string;
  createdBy: string;
}

export interface DownloadUrl {
  url: string;
  expiresAt: string;
}

export const MEDIA_KIND_LABELS: Record<MediaKind, string> = {
  VIDEO: 'Vídeo',
  AUDIO: 'Áudio',
  IMAGE: 'Imagem',
};

export const MEDIA_KIND_ICONS: Record<MediaKind, string> = {
  VIDEO: 'movie',
  AUDIO: 'music_note',
  IMAGE: 'image',
};
