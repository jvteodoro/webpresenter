import { randomUUID } from 'node:crypto';

/** Minimal MediaPlayer speaking the real protocol, to drive the devices UI end to end. */
export class FakePlayer {
  readonly deviceId = randomUUID();
  private readonly received: { type: string; payload: any }[] = [];
  private socket!: WebSocket;

  static async connect(hostname: string): Promise<FakePlayer> {
    const player = new FakePlayer();
    await player.open();
    player.send('HELLO', {
      deviceId: player.deviceId,
      hostname,
      playerVersion: 'e2e',
      protocolVersion: 1,
      displays: [
        { index: 0, width: 1920, height: 1080, scale: 1, primary: true },
        { index: 1, width: 1280, height: 720, scale: 1, primary: false },
      ],
      token: null,
    });
    return player;
  }

  private open(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socket = new WebSocket('ws://localhost:8090/ws/player');
      this.socket.onopen = () => resolve();
      this.socket.onerror = () => reject(new Error('cannot connect fake player'));
      this.socket.onmessage = (event) => this.received.push(JSON.parse(String(event.data)));
    });
  }

  send(type: string, payload: unknown): void {
    this.socket.send(
      JSON.stringify({ type, id: randomUUID(), ts: new Date().toISOString(), payload }),
    );
  }

  /** Waits for the next message of the given type that was not consumed yet. */
  async next(type: string, timeoutMs = 10_000): Promise<any> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const index = this.received.findIndex((m) => m.type === type);
      if (index >= 0) {
        return this.received.splice(index, 1)[0].payload;
      }
      await new Promise((r) => setTimeout(r, 50));
    }
    throw new Error(
      `fake player did not receive ${type}; got ${this.received.map((m) => m.type).join(', ')}`,
    );
  }

  /** Skips earlier plans and returns the PREPARE for the event with this title. */
  async nextPrepareFor(title: string, timeoutMs = 10_000): Promise<any> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const plan = await this.next('PREPARE', deadline - Date.now());
      if (plan.title === title) {
        return plan;
      }
    }
    throw new Error(`no PREPARE for "${title}"`);
  }

  close(): void {
    this.socket.close();
  }
}

/** Access token of a development user, for API calls made by tests. */
export async function apiToken(user = 'admin'): Promise<string> {
  const form = new URLSearchParams({
    client_id: 'webpresenter-dev-cli',
    grant_type: 'password',
    username: user,
    password: user,
  });
  const response = await fetch(
    'http://localhost:8180/realms/webpresenter/protocol/openid-connect/token',
    { method: 'POST', body: form },
  );
  return (await response.json()).access_token as string;
}

/** Removes a device through the API as the development admin (ignores devices already gone). */
export async function deleteDevice(id: string): Promise<void> {
  const token = await apiToken();
  await fetch(`http://localhost:8090/api/devices/${id}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** Approves a pending (connected) device through the API. */
export async function approveDevice(id: string, name: string): Promise<void> {
  const token = await apiToken();
  const response = await fetch(`http://localhost:8090/api/devices/${id}/approve`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!response.ok) {
    throw new Error(`approve failed: ${response.status}`);
  }
}

/** Deletes events and library media whose titles contain the marker (test cleanup, best effort). */
export async function deleteTestData(marker: string): Promise<void> {
  const headers = { Authorization: `Bearer ${await apiToken()}` };
  const events: { id: string; title: string }[] = await fetch('http://localhost:8090/api/events', {
    headers,
  }).then((r) => r.json());
  for (const e of events.filter((x) => x.title.includes(marker))) {
    await fetch(`http://localhost:8090/api/events/${e.id}`, { method: 'DELETE', headers });
  }
  const media: { id: string; title: string }[] = await fetch('http://localhost:8090/api/media', {
    headers,
  }).then((r) => r.json());
  for (const m of media.filter((x) => x.title.includes(marker))) {
    await fetch(`http://localhost:8090/api/media/${m.id}`, { method: 'DELETE', headers });
  }
}

/** Uploads a fixture file to the library as the AV technician; returns the media id. */
export async function uploadMedia(file: string, title: string, mimeType: string): Promise<string> {
  const { readFileSync } = await import('node:fs');
  const form = new FormData();
  form.append(
    'file',
    new Blob([readFileSync(file)], { type: mimeType }),
    `${title}.${file.split('.').pop()}`,
  );
  const response = await fetch('http://localhost:8090/api/media', {
    method: 'POST',
    headers: { Authorization: `Bearer ${await apiToken('tecnico')}` },
    body: form,
  });
  return (await response.json()).id as string;
}

/** Calls the API as a development user and returns the JSON answer. */
export async function api(
  method: string,
  path: string,
  body?: unknown,
  user = 'lider',
): Promise<any> {
  const response = await fetch(`http://localhost:8090${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${await apiToken(user)}`,
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${path} answered ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}
