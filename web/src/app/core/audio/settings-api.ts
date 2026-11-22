import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AudioSettings, MixerLevels } from './background';

@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/settings`;

  audio(): Observable<AudioSettings> {
    return this.http.get<AudioSettings>(`${this.baseUrl}/audio`);
  }

  updateAudio(settings: AudioSettings): Observable<AudioSettings> {
    return this.http.put<AudioSettings>(`${this.baseUrl}/audio`, settings);
  }

  /** Live faders: reach the players at once. */
  updateLevels(levels: MixerLevels): Observable<AudioSettings> {
    return this.http.put<AudioSettings>(`${this.baseUrl}/audio/levels`, levels);
  }
}
