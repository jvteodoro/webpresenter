import { MediaKind } from '../media/media';
import { RecordingKind } from '../songs/song';

export type PlaylistItemType = 'SONG' | 'MEDIA';

export interface PlaylistSummary {
  id: string;
  name: string;
  description: string | null;
  shuffle: boolean;
  itemCount: number;
  updatedAt: string;
}

export interface PlaylistItem {
  id: string;
  type: PlaylistItemType;
  title: string;
  songId: string | null;
  /** Chosen recording; null plays the sung recording (or the first one). */
  recordingId: string | null;
  recordingKind: RecordingKind | null;
  mediaId: string | null;
  kind: MediaKind | null;
  durationMs: number | null;
}

export interface PlaylistDetail {
  id: string;
  name: string;
  description: string | null;
  shuffle: boolean;
  items: PlaylistItem[];
  createdBy: string;
  updatedAt: string;
}

export interface PlaylistItemSpec {
  type: PlaylistItemType;
  songId?: string | null;
  recordingId?: string | null;
  mediaId?: string | null;
}

export function specOf(item: PlaylistItem): PlaylistItemSpec {
  return item.type === 'SONG'
    ? { type: 'SONG', songId: item.songId, recordingId: item.recordingId }
    : { type: 'MEDIA', mediaId: item.mediaId };
}
