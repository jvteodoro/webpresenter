import { Page, expect } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

export const FIXTURES_DIR = join(tmpdir(), 'webpresenter-e2e');

/** Development users from deploy/keycloak/webpresenter-realm.json (password = username). */
export type DevUser = 'admin' | 'tecnico' | 'lider' | 'musico';

const HEADINGS: Record<string, string> = {
  '/biblioteca/midia': 'Biblioteca',
  '/biblioteca/diversos': 'Biblioteca',
  '/biblioteca/mensagens': 'Biblioteca',
  '/eventos': 'Eventos',
  '/dispositivos': 'Dispositivos',
};

/** Opens a page of the app, signs in on the Keycloak page and waits for the page heading. */
export async function login(
  page: Page,
  user: DevUser,
  path: keyof typeof HEADINGS = '/biblioteca/midia',
): Promise<void> {
  await page.goto(path);
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(user);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { level: 1, name: HEADINGS[path] })).toBeVisible();
}

export function mediaRow(page: Page, title: string) {
  return page.locator('tr.mat-mdc-row').filter({ has: page.getByText(title, { exact: true }) });
}

/** From any page: the library's general media section. */
export async function openLibraryMedia(page: Page): Promise<void> {
  await page.getByRole('link', { name: 'Biblioteca', exact: true }).click();
  await page.getByRole('tab', { name: 'Mídia' }).click();
}
