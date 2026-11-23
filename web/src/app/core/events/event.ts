import { CacheStatus } from '../devices/device';
import { MediaKind } from '../media/media';
import { BackgroundSource, BackgroundView } from '../audio/background';

/** SCENE: a marker grouping the items after it (until the next scene); not presented. */
/** SLOT: a liturgy template's variable slot, to be filled for this event; not presented. */
export type ItemType = 'MEDIA' | 'SLIDE' | 'SONG' | 'SCENE' | 'CLOCK' | 'TIMER' | 'SLOT';

export interface EventSummary {
  id: string;
  title: string;
  startsAt: string | null;
  current: boolean;
  itemCount: number;
  updatedAt: string;
  templateId: string | null;
  /** "YYYY-MM-DD" of the template's day. */
  templateDate: string | null;
  pendingSlots: number;
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
  /** For a variable slot: what it expects. */
  slotKind?: 'SONG' | 'MEDIA' | 'SLIDE' | 'ANY' | null;
  /** The item's own slide appearance (null fields inherit the event's, then the song's). */
  style?: StyleView | null;
}

/** What is stored: absent/null fields inherit. */
export interface SlideStyle {
  backgroundMediaId: string | null;
  textColor: string | null;
  textScale: number | null;
}

export interface StyleView extends SlideStyle {
  backgroundTitle: string | null;
  backgroundUrl: string | null;
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
  templateId: string | null;
  templateDate: string | null;
  /** Variable slots still to be filled. */
  pendingSlots: number;
  /** The event's slide appearance (null fields: the songs' own). */
  style?: StyleView | null;
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
