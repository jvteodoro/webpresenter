import { provideHttpClient } from '@angular/common/http';
import { HttpEventType } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { MediaAsset } from './media';
import { MediaApi, UploadProgress } from './media-api';

describe('MediaApi', () => {
  let api: MediaApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(MediaApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uploads as multipart form data and reports progress, then the created asset', () => {
    const file = new File(['abc'], 'Abertura.mp4', { type: 'video/mp4' });
    const events: UploadProgress[] = [];

    api.upload(file).subscribe((e) => events.push(e));

    const req = http.expectOne(`${environment.apiUrl}/api/media`);
    expect(req.request.method).toBe('POST');
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    req.event({ type: HttpEventType.UploadProgress, loaded: 50, total: 200 });
    req.flush({ id: '1', title: 'Abertura' } as MediaAsset);

    expect(events).toEqual([
      { state: 'uploading', percent: 25 },
      { state: 'done', asset: { id: '1', title: 'Abertura' } },
    ]);
  });

  it('renames with a PATCH of the title', () => {
    api.rename('42', 'Novo título').subscribe();

    const req = http.expectOne(`${environment.apiUrl}/api/media/42`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'Novo título' });
    req.flush({});
  });
});
