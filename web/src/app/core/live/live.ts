import { TimerInfo } from './timer';
import { MediaKind } from '../media/media';
import { ItemType } from '../events/event';

export type LiveStatus = 'REST' | 'PLAYING' | 'PAUSED' | 'SHOWING';
export type PlaybackStatus = 'LOADING' | 'PLAYING' | 'PAUSED' | 'SHOWING' | 'ENDED' | 'REST' | 'FAILED';
export type LiveAction = 'PLAY' | 'SLIDE' | 'NEXT' | 'PREVIOUS' | 'PAUSE' | 'RESUME' | 'REST';

export interface LiveSlide {
  label: string;
  lines: string[];
  cue: string | null;
  instrumental: boolean;
}

export interface LiveItem {
  id: string;
  type: ItemType;
  title: string;
  kind: MediaKind | null;
  durationMs: number | null;
  /** Lyrics slides of a SONG item. */
  slides: LiveSlide[];
  hasAudio: boolean;
  timed: boolean;
  /** Name of the scene the item belongs to, or null. */
  scene: string | null;
  /** What a TIMER counts down to. */
  timer: TimerInfo | null;
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
  /** Slide of the song on screen; -1 when the current item is not a song. */
  currentSlide: number;
  status: LiveStatus;
  itemStartedAt: string | null;
  players: LivePlayer[];
  version: number;
}

export function isTimeBased(item: LiveItem | null | undefined): boolean {
  return !!item && (item.kind === 'VIDEO' || item.kind === 'AUDIO' || item.hasAudio);
}
