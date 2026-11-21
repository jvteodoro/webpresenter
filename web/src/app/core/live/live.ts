import { MediaKind } from '../media/media';
import { ItemType } from '../events/event';

export type LiveStatus = 'REST' | 'PLAYING' | 'PAUSED' | 'SHOWING';
export type PlaybackStatus = 'LOADING' | 'PLAYING' | 'PAUSED' | 'SHOWING' | 'ENDED' | 'REST' | 'FAILED';
export type LiveAction = 'PLAY' | 'NEXT' | 'PREVIOUS' | 'PAUSE' | 'RESUME' | 'REST';

export interface LiveItem {
  id: string;
  type: ItemType;
  title: string;
  kind: MediaKind | null;
  durationMs: number | null;
}

export interface LivePlayer {
  id: string;
  name: string;
  online: boolean;
  ready: boolean;
  playback: PlaybackStatus | null;
  itemId: string | null;
  positionMs: number;
  durationMs: number;
  detail: string | null;
}

export interface LiveState {
  eventId: string | null;
  title: string | null;
  items: LiveItem[];
  currentIndex: number;
  status: LiveStatus;
  itemStartedAt: string | null;
  players: LivePlayer[];
  version: number;
}

export function isTimeBased(item: LiveItem | null | undefined): boolean {
  return !!item && (item.kind === 'VIDEO' || item.kind === 'AUDIO');
}
