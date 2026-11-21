import { CacheStatus } from '../devices/device';
import { MediaKind } from '../media/media';

export type ItemType = 'MEDIA' | 'SLIDE' | 'SONG';

export interface EventSummary {
  id: string;
  title: string;
  startsAt: string | null;
  current: boolean;
  itemCount: number;
  updatedAt: string;
}

export interface EventItem {
  id: string;
  type: ItemType;
  title: string;
  media: { id: string; title: string; kind: MediaKind | null; durationMs: number | null } | null;
  slide: { title: string; body: string } | null;
  song: SongItem | null;
}

/** A song in an event: the chosen recording (null: lyrics only) and the ones available. */
export interface SongItem {
  id: string;
  title: string;
  slideCount: number;
  recordingId: string | null;
  recordingKind: 'SUNG' | 'PLAYBACK' | 'OTHER' | null;
  timed: boolean;
  durationMs: number | null;
  recordings: { id: string; kind: 'SUNG' | 'PLAYBACK' | 'OTHER'; timed: boolean }[];
}

export interface EventDetail {
  id: string;
  title: string;
  startsAt: string | null;
  current: boolean;
  items: EventItem[];
  createdBy: string;
  updatedAt: string;
}

export interface EventInput {
  title: string;
  startsAt: string | null;
}

export interface SlideInput {
  slideTitle: string;
  slideBody: string;
}

/** A player's readiness for the current event. */
export interface Readiness {
  id: string;
  name: string;
  online: boolean;
  cacheStatus: CacheStatus | null;
}
