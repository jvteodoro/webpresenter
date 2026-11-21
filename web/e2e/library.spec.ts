import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURES_DIR, login, mediaRow } from './fixtures';

test.describe('Biblioteca de mídia', () => {
  test('técnico envia, visualiza, renomeia, busca e exclui um vídeo', async ({
    page,
  }, testInfo) => {
    const title = `E2E vídeo ${testInfo.project.name} ${Date.now()}`;
    const renamed = `${title} (editado)`;
    await login(page, 'tecnico');

    // Upload through the hidden file input behind the drop zone.
    await page.locator('input[type=file]').setInputFiles({
      name: `${title}.mp4`,
      mimeType: 'video/mp4',
      buffer: readFileSync(join(FIXTURES_DIR, 'clip.mp4')),
    });
    const row = mediaRow(page, title);
    await expect(row).toBeVisible();
    await expect(row).toContainText('0:03');
    await expect(row).toContainText('tecnico');

    // Preview plays from the presigned S3 link.
    await row.getByRole('button', { name: 'Visualizar' }).click();
    const dialog = page.getByRole('dialog');
    const video = dialog.locator('video');
    await expect(video).toBeVisible();
    await expect
      .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState))
      .toBeGreaterThanOrEqual(1);
    expect(await video.evaluate((v: HTMLVideoElement) => Math.round(v.duration))).toBe(3);
    await dialog.getByRole('button', { name: 'Fechar' }).click();
    await expect(dialog).toBeHidden();

    // Rename.
    await row.getByRole('button', { name: 'Mais ações' }).click();
    await page.getByRole('menuitem', { name: 'Renomear' }).click();
    const prompt = page.getByRole('dialog');
    await prompt.getByLabel('Título').fill(renamed);
    await prompt.getByRole('button', { name: 'Salvar' }).click();
    const renamedRow = mediaRow(page, renamed);
    await expect(renamedRow).toBeVisible();

    // Search ignores case and accents.
    await page.getByLabel('Buscar').fill(renamed.toUpperCase().replace('Í', 'I'));
    await expect(page.locator('tr.mat-mdc-row')).toHaveCount(1);
    await page.getByLabel('Buscar').fill('');

    // Delete with confirmation; it disappears and stays gone after a reload.
    await renamedRow.getByRole('button', { name: 'Mais ações' }).click();
    await page.getByRole('menuitem', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(renamedRow).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Biblioteca de mídia' })).toBeVisible();
    await expect(mediaRow(page, renamed)).toHaveCount(0);
  });

  test('filtro por tipo mostra só imagens', async ({ page }, testInfo) => {
    const title = `E2E imagem ${testInfo.project.name} ${Date.now()}`;
    await login(page, 'tecnico');
    await page.locator('input[type=file]').setInputFiles({
      name: `${title}.png`,
      mimeType: 'image/png',
      buffer: readFileSync(join(FIXTURES_DIR, 'slide.png')),
    });
    await expect(mediaRow(page, title)).toContainText('800×450');

    await page.getByRole('radio', { name: 'Imagens' }).click();
    const kinds = await page.locator('tr.mat-mdc-row mat-icon.kind').allTextContents();
    expect(kinds.length).toBeGreaterThan(0);
    expect(new Set(kinds.map((k) => k.trim()))).toEqual(new Set(['image']));

    await page.getByRole('radio', { name: 'Imagens' }).click(); // keep the library tidy
    await mediaRow(page, title).getByRole('button', { name: 'Mais ações' }).click();
    await page.getByRole('menuitem', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(mediaRow(page, title)).toHaveCount(0);
  });

  test('arquivo que não é mídia é recusado com mensagem clara', async ({ page }) => {
    await login(page, 'tecnico');
    await page.locator('input[type=file]').setInputFiles({
      name: 'planilha.xlsx',
      mimeType: 'application/octet-stream',
      buffer: Buffer.from('isto não é mídia'),
    });
    await expect(page.getByText('Formato não suportado')).toBeVisible();
  });

  test('músico vê a biblioteca mas não pode alterá-la', async ({ page }) => {
    await login(page, 'musico');

    await expect(page.getByText('Arraste vídeos, áudios ou imagens para cá')).toHaveCount(0);
    await expect(page.locator('input[type=file]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mais ações' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Visualizar' }).first()).toBeVisible();
  });

  test('usuário vê seu nome e consegue sair', async ({ page }) => {
    await login(page, 'lider');
    await page.getByRole('button', { name: /Líder de Louvor/ }).click();
    await page.getByRole('menuitem', { name: 'Sair' }).click();
    await expect(page.locator('#kc-login')).toBeVisible();
  });
});
