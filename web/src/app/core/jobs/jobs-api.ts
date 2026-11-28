import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ImportFormat, ImportTarget, Job } from './job';

@Injectable({ providedIn: 'root' })
export class JobsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/jobs`;

  importFromUrl(url: string, target: ImportTarget, format: ImportFormat, title: string | null): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/youtube-import`, { url, target, format, title });
  }

  separatePlayback(songId: string, recordingId: string): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/separate-playback`, { songId, recordingId });
  }

  /** Choir voices (soprano, alto, tenor, bass…) of the vocals track. */
  separateVoices(songId: string, recordingId: string): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/separate-voices`, { songId, recordingId });
  }

  /** Lyrics (when the song has none) and slide times from a sung recording. */
  transcribeLyrics(songId: string, recordingId: string, fresh = false): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/transcribe-lyrics`, { songId, recordingId, fresh });
  }

  /** Lyrics and slide times read from the on-screen text of a video (the recording is the one the video follows). */
  extractLyricsFromVideo(songId: string, recordingId: string, mediaId: string, fresh = false): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/extract-lyrics-from-video`, { songId, recordingId, mediaId, fresh });
  }

  cancel(id: string): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/${id}/cancel`, {});
  }

  retry(id: string): Observable<Job> {
    return this.http.post<Job>(`${this.baseUrl}/${id}/retry`, {});
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
