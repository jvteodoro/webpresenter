import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { CurrentUser } from './core/auth/current-user';
import { UiModeService } from './core/ui-mode';

export const routes: Routes = [
  // The start page depends on the interface mode (operation → live, administration → events).
  // Waits for the user's roles, which decide the default mode.
  {
    path: '',
    pathMatch: 'full',
    children: [],
    canActivate: [
      async () => {
        const router = inject(Router);
        const mode = inject(UiModeService);
        await inject(CurrentUser).load().catch(() => undefined);
        return router.parseUrl(mode.startPage());
      },
    ],
  },
  {
    path: 'ao-vivo',
    title: 'Ao vivo · webpresenter',
    loadComponent: () => import('./features/live/live-page').then((m) => m.LivePage),
  },
  {
    path: 'eventos',
    title: 'Eventos · webpresenter',
    loadComponent: () => import('./features/events/events-page').then((m) => m.EventsPage),
  },
  {
    path: 'eventos/:id',
    title: 'Evento · webpresenter',
    loadComponent: () => import('./features/events/event-editor/event-editor-page').then((m) => m.EventEditorPage),
  },
  {
    path: 'musicas',
    title: 'Músicas · webpresenter',
    loadComponent: () => import('./features/songs/songs-page').then((m) => m.SongsPage),
  },
  {
    path: 'musicas/:id',
    title: 'Música · webpresenter',
    loadComponent: () => import('./features/songs/song-page/song-page').then((m) => m.SongPage),
  },
  {
    path: 'biblioteca/albuns/:id',
    title: 'Álbum · webpresenter',
    loadComponent: () => import('./features/library/albums/album-page').then((m) => m.AlbumPage),
  },
  {
    path: 'biblioteca/playlists/:id',
    title: 'Playlist · webpresenter',
    loadComponent: () => import('./features/library/playlists/playlist-page').then((m) => m.PlaylistPage),
  },
  {
    path: 'biblioteca',
    title: 'Biblioteca · webpresenter',
    loadComponent: () => import('./features/library/library-page').then((m) => m.LibraryPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'albuns' },
      {
        path: 'albuns',
        title: 'Álbuns · webpresenter',
        loadComponent: () => import('./features/library/albums/albums-section').then((m) => m.AlbumsSection),
      },
      {
        path: 'playlists',
        title: 'Playlists · webpresenter',
        loadComponent: () => import('./features/library/playlists/playlists-section').then((m) => m.PlaylistsSection),
      },
      {
        path: 'avulsas',
        title: 'Músicas avulsas · webpresenter',
        loadComponent: () => import('./features/library/loose-songs/loose-songs-section').then((m) => m.LooseSongsSection),
      },
      {
        path: 'fundos',
        title: 'Fundos musicais · webpresenter',
        loadComponent: () => import('./features/library/media-section/media-section').then((m) => m.MediaSection),
        data: {
          purpose: 'BACKGROUND_MUSIC',
          accept: 'audio/*,video/*',
          hint: 'Arraste músicas instrumentais para tocar como fundo musical',
          emptyText: 'Nenhum fundo musical ainda.',
          kindFilter: false,
          importTarget: 'BACKGROUND_MUSIC',
        },
      },
      {
        path: 'efeitos',
        title: 'Efeitos sonoros · webpresenter',
        loadComponent: () => import('./features/library/media-section/media-section').then((m) => m.MediaSection),
        data: {
          purpose: 'SOUND_EFFECT',
          accept: 'audio/*',
          hint: 'Arraste sons curtos (sinos, alertas) para cronômetros e avisos',
          emptyText: 'Nenhum efeito sonoro ainda.',
          kindFilter: false,
          importTarget: 'SOUND_EFFECT',
        },
      },
      {
        path: 'midia',
        title: 'Mídia · webpresenter',
        loadComponent: () => import('./features/library/media-section/media-section').then((m) => m.MediaSection),
        data: { purpose: 'GENERAL', importTarget: 'MEDIA' },
      },
    ],
  },
  {
    path: 'calendario',
    title: 'Calendário · webpresenter',
    loadComponent: () => import('./features/calendar/calendar-page').then((m) => m.CalendarPage),
  },
  {
    path: 'liturgia',
    title: 'Liturgia · webpresenter',
    loadComponent: () => import('./features/liturgy/liturgy-page').then((m) => m.LiturgyPage),
  },
  {
    path: 'liturgia/:id',
    title: 'Modelo de liturgia · webpresenter',
    loadComponent: () => import('./features/liturgy/template-page').then((m) => m.TemplatePage),
  },
  {
    path: 'configuracoes',
    title: 'Configurações · webpresenter',
    loadComponent: () => import('./features/settings/settings-page').then((m) => m.SettingsPage),
  },
  {
    path: 'fila',
    title: 'Fila · webpresenter',
    loadComponent: () => import('./features/jobs/jobs-page').then((m) => m.JobsPage),
  },
  {
    path: 'dispositivos',
    title: 'Dispositivos · webpresenter',
    loadComponent: () => import('./features/devices/devices-page').then((m) => m.DevicesPage),
  },
  { path: '**', redirectTo: 'eventos' },
];
