import { HttpClient, HttpEvent, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DownloadUrl, MediaAsset, MediaPurpose } from './media';

/** Progress of an upload: percentage while sending, then the created asset. */
export type UploadProgress = { state: 'uploading'; percent: number } | { state: 'done'; asset: MediaAsset };

@Injectable({ providedIn: 'root' })
export class MediaApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/media`;

  /** Library files with any of the given purposes (general media by default). */
  list(purposes: MediaPurpose[] = ['GENERAL']): Observable<MediaAsset[]> {
    return this.http.get<MediaAsset[]>(this.baseUrl, { params: { purpose: purposes.join(',') } });
  }

  upload(file: File, title?: string, purpose: MediaPurpose = 'GENERAL'): Observable<UploadProgress> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('purpose', purpose);
    if (title) {
      form.append('title', title);
    }
    return this.http
      .post<MediaAsset>(this.baseUrl, form, { observe: 'events', reportProgress: true })
      .pipe(
        filter((event) => event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response),
        map((event: HttpEvent<MediaAsset>): UploadProgress => {
          if (event.type === HttpEventType.Response) {
            return { state: 'done', asset: event.body as MediaAsset };
          }
          const progress = event as { loaded: number; total?: number };
          const percent = progress.total ? Math.round((100 * progress.loaded) / progress.total) : 0;
          return { state: 'uploading', percent };
        }),
      );
  }

  rename(id: string, title: string): Observable<MediaAsset> {
    return this.http.patch<MediaAsset>(`${this.baseUrl}/${id}`, { title });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  downloadUrl(id: string): Observable<DownloadUrl> {
    return this.http.get<DownloadUrl>(`${this.baseUrl}/${id}/download-url`);
  }
}
