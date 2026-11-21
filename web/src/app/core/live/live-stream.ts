import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { environment } from '../../../environments/environment';
import { LiveState } from './live';

export type StreamStatus = 'connecting' | 'open' | 'reconnecting';

/**
 * Follows the live state through the server's Server-Sent Events stream. Uses fetch rather than
 * EventSource so the access token goes in the Authorization header; reconnects with backoff and
 * refreshes the token before every connection.
 */
@Injectable({ providedIn: 'root' })
export class LiveStream implements OnDestroy {
  private readonly keycloak = inject(Keycloak);

  private readonly stateSignal = signal<LiveState | null>(null);
  private readonly statusSignal = signal<StreamStatus>('connecting');
  /** Local time when the current state arrived (for interpolating media positions). */
  private readonly receivedAtSignal = signal(0);

  readonly state = this.stateSignal.asReadonly();
  readonly status = this.statusSignal.asReadonly();
  readonly receivedAt = this.receivedAtSignal.asReadonly();

  private controller: AbortController | null = null;
  private users = 0;
  private retryMs = 1000;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  /** Starts streaming while at least one consumer is attached. */
  attach(): void {
    if (this.users++ === 0) {
      void this.connect();
    }
  }

  detach(): void {
    if (--this.users <= 0) {
      this.users = 0;
      this.stop();
    }
  }

  /** Applies a state returned by a command, unless the stream already delivered a newer one. */
  apply(state: LiveState): void {
    const current = this.stateSignal();
    if (!current || state.version >= current.version) {
      this.stateSignal.set(state);
      this.receivedAtSignal.set(Date.now());
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private async connect(): Promise<void> {
    this.controller = new AbortController();
    const signal = this.controller.signal;
    try {
      await this.keycloak.updateToken(30);
      const response = await fetch(`${environment.apiUrl}/api/live/stream`, {
        headers: { Authorization: `Bearer ${this.keycloak.token}`, Accept: 'text/event-stream' },
        signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`stream answered ${response.status}`);
      }
      this.statusSignal.set('open');
      this.retryMs = 1000;
      await this.read(response.body, signal);
    } catch {
      // network error, server restart or abort: handled below
    }
    if (!signal.aborted && this.users > 0) {
      this.statusSignal.set('reconnecting');
      this.retryTimer = setTimeout(() => void this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 5000);
    }
  }

  /** Minimal SSE parser: events separated by blank lines, "data:" lines joined. */
  private async read(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) {
        return;
      }
      buffer += decoder.decode(value, { stream: true });
      let boundary: number;
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
        const data = block
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).replace(/^ /, ''))
          .join('\n');
        if (data) {
          this.apply(JSON.parse(data) as LiveState);
        }
      }
    }
  }

  private stop(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.controller?.abort();
    this.controller = null;
    this.statusSignal.set('connecting');
  }
}
