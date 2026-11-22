import { CacheStatus } from '../devices/device';
import { MediaKind } from '../media/media';
import { BackgroundSource, BackgroundView } from '../audio/background';

/** SCENE: a marker grouping the items after it (until the next scene); not presented. */
export type ItemType = 'MEDIA' | 'SLIDE' | 'SONG' | 'SCENE' | 'CLOCK' | 'TIMER';

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
  scene: SceneInfo | null;
  /** The item's (or scene's) own background music; null inherits. */
  background: BackgroundView | null;
  /** Shown above a clock or timer. */
  label: string | null;
  timer: TimerView | null;
}

export type TimerMode = 'SCENE_END' | 'TIME' | 'DURATION';

export interface TimerSettings {
  mode: TimerMode;
  /** "HH:mm" for TIME. */
  time: string | null;
  durationSeconds: number | null;
  signals: { beforeEndSeconds: number; soundMediaId: string }[];
}

export interface TimerView {
  mode: TimerMode;
  time: string | null;
  durationSeconds: number | null;
  signals: { beforeEndSeconds: number; soundMediaId: string; sound: string }[];
}

export interface SceneInfo {
  title: string;
  /** "HH:mm:ss" or null. */
  starts: string | null;
  ends: string | null;
}

export interface SceneInput {
  title: string;
  starts: string | null;
  ends: string | null;
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
  /** The event's own background music; null uses the platform's. */
  background: BackgroundView | null;
}

export interface EventInput {
  title: string;
  startsAt: string | null;
}

export type { BackgroundSource };

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
