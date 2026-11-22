import { BackgroundSource } from '../audio/background';
import { ItemType, TimerSettings } from '../events/event';

/** ISO weekday: 1 = Monday … 7 = Sunday. As the server names them. */
export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
export const WEEKDAYS: Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const WEEKDAY_LABELS: Record<Weekday, string> = {
  MONDAY: 'Segunda', TUESDAY: 'Terça', WEDNESDAY: 'Quarta', THURSDAY: 'Quinta', FRIDAY: 'Sexta', SATURDAY: 'Sábado', SUNDAY: 'Domingo',
};

export type SlotKind = 'SONG' | 'MEDIA' | 'SLIDE' | 'ANY';
export const SLOT_KIND_LABELS: Record<SlotKind, string> = {
  SONG: 'Uma música', MEDIA: 'Uma mídia', SLIDE: 'Um slide', ANY: 'Música, mídia ou slide',
};

/** An item described by value: a template slot, or what fills an event's variable slot. */
export interface ItemSpec {
  type: ItemType;
  mediaId?: string | null;
  slideTitle?: string | null;
  slideBody?: string | null;
  songId?: string | null;
  recordingId?: string | null;
  sceneTitle?: string | null;
  sceneStarts?: string | null;
  sceneEnds?: string | null;
  label?: string | null;
  timer?: TimerSettings | null;
  background?: BackgroundSource | null;
  slotKind?: SlotKind | null;
}

export interface TemplateSummary {
  id: string;
  name: string;
  weekdays: Weekday[];
  startTime: string | null;
  slotCount: number;
  variableSlots: number;
  updatedAt: string;
}

export interface SlotView {
  spec: ItemSpec;
  title: string;
  detail: string;
  backgroundName: string | null;
}

export interface TemplateDetail {
  id: string;
  name: string;
  weekdays: Weekday[];
  startTime: string | null;
  slots: SlotView[];
  updatedAt: string;
}
