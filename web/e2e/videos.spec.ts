import { expect, test } from '@playwright/test';
import { login } from './fixtures';

test.describe('Diversos e vídeos recorrentes', () => {
  test('técnico liga um canal recorrente, vê a lista e o remove', async ({ page }, testInfo) => {
    const stamp = `${testInfo.project.name}-${Date.now()}`;
    const name = `Canal E2E ${stamp}`;
    await login(page, 'tecnico', '/biblioteca/diversos');
    await expect(page.getByRole('tab', { name: 'Diversos' })).toHaveAttribute('aria-selected', 'true');

    await page.getByRole('button', { name: 'Vídeos recorrentes' }).click();
    const dialog = page.getByRole('dialog').last();
    await dialog.getByRole('textbox', { name: 'Nome' }).fill(name);
    await dialog.getByRole('textbox', { name: 'Link do canal' }).fill(`https://www.youtube.com/@e2e-${stamp}`);
    await dialog.getByRole('button', { name: 'Adicionar' }).click();

    const source = dialog.locator('li.source').filter({ hasText: name });
    await expect(source).toBeVisible();
    await expect(source.getByRole('link')).toContainText(`@e2e-${stamp}`);
    await page.screenshot({ path: testInfo.outputPath('vídeos-recorrentes.png') });

    await source.getByRole('button', { name: 'Remover canal' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'Remover' }).click();
    await expect(dialog.locator('li.source').filter({ hasText: name })).toHaveCount(0);
  });

  test('um link que não é de canal do YouTube é recusado', async ({ page }) => {
    await login(page, 'tecnico', '/biblioteca/diversos');
    await page.getByRole('button', { name: 'Vídeos recorrentes' }).click();
    const dialog = page.getByRole('dialog').last();
    await dialog.getByRole('textbox', { name: 'Nome' }).fill('Não é YouTube');
    await dialog.getByRole('textbox', { name: 'Link do canal' }).fill('https://example.com/canal');
    await dialog.getByRole('button', { name: 'Adicionar' }).click();
    await expect(page.getByText('canal do YouTube')).toBeVisible();
  });
});
