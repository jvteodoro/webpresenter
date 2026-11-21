import { Page, expect } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

export const FIXTURES_DIR = join(tmpdir(), 'webpresenter-e2e');

/** Development users from deploy/keycloak/webpresenter-realm.json (password = username). */
export type DevUser = 'admin' | 'tecnico' | 'lider' | 'musico';

/** Opens the app, signs in on the Keycloak page and waits for the library. */
export async function login(page: Page, user: DevUser): Promise<void> {
  await page.goto('/');
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(user);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'Biblioteca de mídia' })).toBeVisible();
}

export function mediaRow(page: Page, title: string) {
  return page.locator('tr.mat-mdc-row').filter({ has: page.getByText(title, { exact: true }) });
}
