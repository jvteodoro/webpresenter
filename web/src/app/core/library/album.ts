import { RecordingKind, Source } from '../songs/song';

export interface Album {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  group: string | null;
  source: Source;
  coverMediaId: string | null;
  /** Short-lived direct link to the cover image, or null. */
  coverUrl: string | null;
  trackCount: number;
}

export interface AlbumTrack {
  number: string | null;
  songId: string;
  title: string;
  recordings: RecordingKind[];
}

export interface AlbumDetail {
  album: Album;
  tracks: AlbumTrack[];
}

export interface AlbumInput {
  name: string;
  description: string | null;
  group: string | null;
  coverMediaId: string | null;
}
