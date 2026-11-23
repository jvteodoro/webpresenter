export type JobType = 'YOUTUBE_IMPORT' | 'SEPARATE_PLAYBACK' | 'TRANSCRIBE_LYRICS';
export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';
export type ImportTarget = 'MEDIA' | 'BACKGROUND_MUSIC' | 'SOUND_EFFECT' | 'SONG';
export type ImportFormat = 'AUDIO' | 'VIDEO';

export interface Job {
  id: string;
  type: JobType;
  status: JobStatus;
  title: string;
  params: Record<string, unknown>;
  /** Ids of what the job created: mediaId, songId, recordingId. */
  result: { mediaId?: string; songId?: string; recordingId?: string } | null;
  progress: number;
  message: string | null;
  attempt: number;
  createdBy: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  updatedAt: string;
}

export const JOB_STATUS_LABELS: Record<JobStatus, string> = {
  QUEUED: 'Na fila',
  RUNNING: 'Processando',
  DONE: 'Concluído',
  FAILED: 'Falhou',
  CANCELLED: 'Cancelado',
};

export const JOB_TYPE_ICONS: Record<JobType, string> = {
  YOUTUBE_IMPORT: 'download',
  SEPARATE_PLAYBACK: 'graphic_eq',
  TRANSCRIBE_LYRICS: 'subtitles',
};

export const IMPORT_TARGET_LABELS: Record<ImportTarget, string> = {
  MEDIA: 'Mídia',
  BACKGROUND_MUSIC: 'Fundo musical',
  SOUND_EFFECT: 'Efeito sonoro',
  SONG: 'Nova música',
};

export function isActive(job: Job): boolean {
  return job.status === 'QUEUED' || job.status === 'RUNNING';
}
