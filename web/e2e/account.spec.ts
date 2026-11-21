import { expect, test } from '@playwright/test';
import { login } from './fixtures';

test.describe('Conta do usuário', () => {
  test('menu leva à troca de senha do Keycloak e volta ao painel', async ({ page }) => {
    await login(page, 'musico', '/eventos');

    await page.getByRole('button', { name: 'Músico Banda' }).click();
    await page.getByRole('menuitem', { name: 'Trocar senha' }).click();

    // Keycloak's update-password page (application-initiated action).
    await expect(page.locator('#password-new')).toBeVisible();
    await expect(page.locator('#password-confirm')).toBeVisible();

    // Cancelling returns to the page the user was on.
    await page.locator('[name="cancel-aia"]').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();
  });

  test('menu abre o console de conta', async ({ page }) => {
    await login(page, 'musico', '/eventos');
    await page.getByRole('button', { name: 'Músico Banda' }).click();
    await page.getByRole('menuitem', { name: 'Minha conta' }).click();
    await expect(page).toHaveURL(/\/realms\/webpresenter\/account/);
  });
});
