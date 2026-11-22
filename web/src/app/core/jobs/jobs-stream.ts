import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { environment } from '../../../environments/environment';
import { readSse } from '../sse';
import { Job, isActive } from './job';

/** Follows the processing queue live (snapshot, then each change) while someone is attached. */
@Injectable({ providedIn: 'root' })
export class JobsStream implements OnDestroy {
  private readonly keycloak = inject(Keycloak);
  private readonly byId = signal<ReadonlyMap<string, Job>>(new Map());
  private readonly connected = signal(false);

  /** Newest first. */
  readonly jobs = computed(() => [...this.byId().values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
  readonly activeCount = computed(() => this.jobs().filter(isActive).length);
  readonly isConnected = this.connected.asReadonly();

  private controller: AbortController | null = null;
  private users = 0;
  private retryMs = 1000;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

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

  /** Applies a job returned by a command, unless the stream already has a newer version. */
  apply(job: Job): void {
    this.byId.update((jobs) => {
      const current = jobs.get(job.id);
      if (current && current.updatedAt > job.updatedAt) {
        return jobs;
      }
      return new Map(jobs).set(job.id, job);
    });
  }

  remove(id: string): void {
    this.byId.update((jobs) => {
      const next = new Map(jobs);
      next.delete(id);
      return next;
    });
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private async connect(): Promise<void> {
    this.controller = new AbortController();
    const signal = this.controller.signal;
    try {
      await this.keycloak.updateToken(30);
      const response = await fetch(`${environment.apiUrl}/api/jobs/stream`, {
        headers: { Authorization: `Bearer ${this.keycloak.token}`, Accept: 'text/event-stream' },
        signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`stream answered ${response.status}`);
      }
      this.connected.set(true);
      this.retryMs = 1000;
      await readSse(response.body, signal, (e) => {
        if (e.event === 'snapshot') {
          this.byId.set(new Map((JSON.parse(e.data) as Job[]).map((j) => [j.id, j])));
        } else if (e.event === 'job') {
          this.apply(JSON.parse(e.data) as Job);
        } else if (e.event === 'removed') {
          this.remove((JSON.parse(e.data) as { id: string }).id);
        }
      });
    } catch {
      // network error, server restart or abort: handled below
    }
    this.connected.set(false);
    if (!signal.aborted && this.users > 0) {
      this.retryTimer = setTimeout(() => void this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 10000);
    }
  }

  private stop(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.controller?.abort();
    this.controller = null;
    this.connected.set(false);
  }
}
