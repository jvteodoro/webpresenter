import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURES_DIR, login, mediaRow } from './fixtures';

test.describe('Mensagens musicais', () => {
  test('técnico envia o áudio e o vídeo de um cantor como foram enviados', async ({ page }, testInfo) => {
    const stamp = `${testInfo.project.name} ${Date.now()}`;
    await login(page, 'tecnico', '/biblioteca/mensagens');
    await expect(page.getByRole('tab', { name: 'Mensagens musicais' })).toHaveAttribute('aria-selected', 'true');

    const audio = `Mensagem áudio ${stamp}`;
    await page.locator('input[type=file]').setInputFiles({
      name: `${audio}.m4a`,
      mimeType: 'audio/mp4',
      buffer: readFileSync(join(FIXTURES_DIR, 'song.m4a')),
    });
    await expect(mediaRow(page, audio)).toBeVisible();

    const video = `Mensagem vídeo ${stamp}`;
    await page.locator('input[type=file]').setInputFiles({
      name: `${video}.mp4`,
      mimeType: 'video/mp4',
      buffer: readFileSync(join(FIXTURES_DIR, 'clip.mp4')),
    });
    const row = mediaRow(page, video);
    await expect(row).toBeVisible();
    await expect(row).toContainText('0:03');
  });
});
