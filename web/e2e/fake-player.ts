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
    this.socket.send(JSON.stringify({ type, id: randomUUID(), ts: new Date().toISOString(), payload }));
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
    throw new Error(`fake player did not receive ${type}; got ${this.received.map((m) => m.type).join(', ')}`);
  }

  close(): void {
    this.socket.close();
  }
}

/** Removes a device through the API as the development admin (ignores devices already gone). */
export async function deleteDevice(id: string): Promise<void> {
  const form = new URLSearchParams({ client_id: 'webpresenter-dev-cli', grant_type: 'password', username: 'admin', password: 'admin' });
  const token = await fetch('http://localhost:8180/realms/webpresenter/protocol/openid-connect/token', { method: 'POST', body: form })
    .then((r) => r.json())
    .then((j) => j.access_token as string);
  await fetch(`http://localhost:8090/api/devices/${id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } });
}
