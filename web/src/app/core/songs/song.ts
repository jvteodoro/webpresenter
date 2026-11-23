import { MediaKind } from '../media/media';

export type PartKind = 'VERSE' | 'CHORUS' | 'PRE_CHORUS' | 'BRIDGE' | 'INTRO' | 'OUTRO' | 'TAG' | 'INSTRUMENTAL' | 'OTHER';
export type RecordingKind = 'SUNG' | 'PLAYBACK' | 'OTHER';
export type Source = 'MANUAL' | 'IMPORT' | 'PIPELINE';
export type SongStatus = 'DRAFT' | 'READY';

export interface Slide {
  lines: string[];
  cue: string | null;
}

export interface Part {
  id: string;
  kind: PartKind;
  label: string;
  slides: Slide[];
}

export interface Lyrics {
  parts: Part[];
  order: string[];
}

export interface SequenceSlide {
  index: number;
  partId: string;
  kind: PartKind;
  partLabel: string;
  slideInPart: number;
  lines: string[];
  cue: string | null;
}

export interface Cue {
  slide: number;
  startMs: number;
}

export interface Timing {
  lyricsVersion: number | null;
  source: Source;
  reviewed: boolean;
  cues: Cue[];
}

export interface MediaRef {
  id: string;
  title: string;
  kind: MediaKind | null;
  durationMs: number | null;
}

export interface Recording {
  id: string;
  kind: RecordingKind;
  source: Source;
  media: MediaRef;
  timing: Timing | null;
  timingCurrent: boolean;
}

export interface CollectionRef {
  slug: string;
  name: string;
  group: string | null;
  number: string | null;
}

export interface SongSummary {
  id: string;
  title: string;
  language: string;
  status: SongStatus;
  collections: CollectionRef[];
  recordings: RecordingKind[];
  slideCount: number;
  firstLine: string | null;
}

export interface SongPage {
  songs: SongSummary[];
  total: number;
  offset: number;
}

export interface SongDetail {
  id: string;
  title: string;
  alternativeTitles: string | null;
  language: string;
  authors: string | null;
  copyright: string | null;
  status: SongStatus;
  source: Source;
  origin: string | null;
  externalId: string | null;
  background: MediaRef | null;
  /** Default text colour ("#rrggbb") and size (percent) of this song's slides; null = platform default. */
  textColor: string | null;
  textScale: number | null;
  collections: CollectionRef[];
  lyrics: Lyrics;
  lyricsVersion: number;
  slideSequence: SequenceSlide[];
  recordings: Recording[];
  updatedAt: string;
}

export interface SongCollection {
  slug: string;
  name: string;
  group: string | null;
}

/** Body of PUT /api/songs/{id}. */
export interface SongInput {
  title: string;
  alternativeTitles: string | null;
  language: string;
  authors: string | null;
  copyright: string | null;
  backgroundMediaId: string | null;
  status: SongStatus;
  lyrics: Lyrics;
  collections: { slug: string; name: string; group: string | null; number: string | null }[];
  textColor: string | null;
  textScale: number | null;
}

export const RECORDING_LABELS: Record<RecordingKind, string> = {
  SUNG: 'Cantado',
  PLAYBACK: 'Playback',
  OTHER: 'Outra',
};

export function inputOf(song: SongDetail, changes: Partial<SongInput> = {}): SongInput {
  return {
    title: song.title,
    alternativeTitles: song.alternativeTitles,
    language: song.language,
    authors: song.authors,
    copyright: song.copyright,
    backgroundMediaId: song.background?.id ?? null,
    status: song.status,
    lyrics: song.lyrics,
    collections: song.collections.map((c) => ({ slug: c.slug, name: c.name, group: c.group, number: c.number })),
    textColor: song.textColor ?? null,
    textScale: song.textScale ?? null,
    ...changes,
  };
}
