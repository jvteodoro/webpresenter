import { expect, test } from '@playwright/test';

async function signIn(page: import('@playwright/test').Page, user: string): Promise<void> {
  await page.goto('/');
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(user);
  await page.locator('#kc-login').click();
}

test.describe('Modos da interface', () => {
  test('técnico começa no modo administrativo e alterna para operação', async ({ page }) => {
    await signIn(page, 'tecnico');
    await expect(page.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();
    const nav = page.getByRole('navigation').first();
    await expect(nav.getByRole('link', { name: 'Biblioteca' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Dispositivos' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Ao vivo' })).toHaveCount(0);

    await page.getByRole('radio', { name: 'Modo de operação' }).click();
    await expect(page).toHaveURL(/\/ao-vivo$/);
    await expect(nav.getByRole('link', { name: 'Ao vivo' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Biblioteca' })).toHaveCount(0);

    // Remembered: the start page follows the chosen mode.
    await page.goto('/');
    await expect(page).toHaveURL(/\/ao-vivo$/);
    await page.getByRole('radio', { name: 'Modo administrativo' }).click();
    await expect(page).toHaveURL(/\/eventos$/);
  });

  test('líder começa no modo de operação, na tela ao vivo', async ({ page }) => {
    await signIn(page, 'lider');
    await expect(page).toHaveURL(/\/ao-vivo$/);
    await expect(page.getByRole('radio', { name: 'Modo de operação' })).toBeChecked();
  });
});
