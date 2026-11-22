import { MediaKind } from '../media/media';

export type DeviceStatus = 'PENDING' | 'APPROVED';

export interface DisplayInfo {
  index: number;
  width: number;
  height: number;
  scale: number;
  primary: boolean;
}

export interface CacheStatus {
  eventId: string | null;
  itemsReady: number;
  itemsFailed: number;
  itemsTotal: number;
  bytesReady: number;
  bytesTotal: number;
}

export interface Device {
  id: string;
  name: string;
  hostname: string;
  status: DeviceStatus;
  pairingCode: string | null;
  online: boolean;
  playerVersion: string | null;
  displays: DisplayInfo[];
  displayIndex: number;
  restScreen: { mediaId: string; title: string; kind: MediaKind } | null;
  /** Shows the time over the rest screen. */
  restClock: boolean;
  cacheStatus: CacheStatus | null;
  lastSeenAt: string;
  approvedAt: string | null;
  approvedBy: string | null;
}

export interface DeviceSettings {
  name: string;
  displayIndex: number;
  restMediaId: string | null;
  restClock: boolean;
}

/** "Tela 2 · 1920×1080 (principal)", using physical pixels as operators know them. */
export function displayLabel(display: DisplayInfo): string {
  const width = Math.round(display.width * display.scale);
  const height = Math.round(display.height * display.scale);
  return `Tela ${display.index + 1} · ${width}×${height}${display.primary ? ' (principal)' : ''}`;
}
