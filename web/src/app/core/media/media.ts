export type MediaKind = 'VIDEO' | 'AUDIO' | 'IMAGE';

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
