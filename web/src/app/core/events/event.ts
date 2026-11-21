import { CacheStatus } from '../devices/device';
import { MediaKind } from '../media/media';

export type ItemType = 'MEDIA' | 'SLIDE';

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
