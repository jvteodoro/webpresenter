import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** One day's download of a source's video: QUEUED, DONE (mediaId set) or FAILED (message says why). */
export interface VideoSourceImport {
  eventDate: string;
  status: 'QUEUED' | 'DONE' | 'FAILED';
  message: string | null;
  mediaId: string | null;
  lastAttemptAt: string;
}

/** A YouTube channel whose video of each event date is downloaded into the misc section. */
export interface VideoSource {
  id: string;
  name: string;
  channelUrl: string;
  enabled: boolean;
  createdBy: string;
  createdAt: string;
  imports: VideoSourceImport[];
}

export interface VideoSourceRequest {
  name: string;
  channelUrl: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class VideoSourcesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/video-sources`;

  list(): Observable<VideoSource[]> {
    return this.http.get<VideoSource[]>(this.baseUrl);
  }

  create(request: VideoSourceRequest): Observable<VideoSource> {
    return this.http.post<VideoSource>(this.baseUrl, request);
  }

  update(id: string, request: VideoSourceRequest): Observable<VideoSource> {
    return this.http.put<VideoSource>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Downloads the source's video of a date now, instead of waiting for the daily search. */
  importDay(id: string, date: string): Observable<VideoSourceImport> {
    return this.http.post<VideoSourceImport>(`${this.baseUrl}/${id}/imports`, { date });
  }
}
