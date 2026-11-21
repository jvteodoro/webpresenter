import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'biblioteca' },
  {
    path: 'biblioteca',
    title: 'Biblioteca · webpresenter',
    loadComponent: () => import('./features/library/library-page').then((m) => m.LibraryPage),
  },
  { path: '**', redirectTo: 'biblioteca' },
];
