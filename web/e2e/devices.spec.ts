import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FakePlayer, deleteDevice } from './fake-player';
import { FIXTURES_DIR, login, mediaRow, openLibraryMedia } from './fixtures';

const created: FakePlayer[] = [];

/** Registers a fake player whose device record must not outlive the test, even if it fails. */
function cleanupAfter(player: FakePlayer): void {
  created.push(player);
}

test.afterEach(async () => {
  for (const player of created.splice(0)) {
    player.close();
    await deleteDevice(player.deviceId);
  }
});

test.describe('Dispositivos', () => {
  test('técnico aprova, configura e remove um player', async ({ page }, testInfo) => {
    const hostname = `e2e-${testInfo.project.name}-${Date.now()}`;
    const name = `Telão ${testInfo.project.name} ${Date.now()}`;
    const restTitle = `E2E descanso ${testInfo.project.name} ${Date.now()}`;
    const player = await FakePlayer.connect(hostname);
    cleanupAfter(player);
    try {
      const { pairingCode } = await player.next('PAIRING_PENDING');
      await login(page, 'tecnico');

      // A rest screen candidate in the library.
      await page.locator('input[type=file]').setInputFiles({
        name: `${restTitle}.png`,
        mimeType: 'image/png',
        buffer: readFileSync(join(FIXTURES_DIR, 'slide.png')),
      });
      await expect(mediaRow(page, restTitle)).toBeVisible();

      // The pending player shows the same code as its screen.
      await page.getByRole('link', { name: 'Dispositivos' }).click();
      const pendingCard = page.getByRole('article', { name: hostname });
      await expect(pendingCard).toContainText(pairingCode);
      await expect(pendingCard).toContainText('Online');

      // Approve by the code on its screen (case and dash do not matter); a wrong code says so.
      const codeField = page.getByLabel('Código exibido no telão');
      await codeField.fill('ZZZ-999');
      await page.getByRole('button', { name: 'Aprovar por código' }).click();
      await expect(page.getByRole('alert')).toContainText('Nenhum player aguardando aprovação com esse código');
      await codeField.fill(pairingCode.replace('-', '').toLowerCase());
      await page.getByRole('button', { name: 'Aprovar por código' }).click();
      const prompt = page.getByRole('dialog');
      await prompt.getByLabel('Nome do player').fill(name);
      await prompt.getByRole('button', { name: 'Salvar' }).click();
      expect((await player.next('PAIRED')).token).toBeTruthy();
      expect((await player.next('CONFIG')).deviceName).toBe(name);

      const card = page.getByRole('article', { name });
      await expect(card).toContainText('Tela 1 · 1920×1080 (principal)');
      await expect(card).toContainText('Tela preta');

      // Configure display and rest screen: pushed to the player at once.
      await card.getByRole('button', { name: 'Configurar' }).click();
      const settings = page.getByRole('dialog');
      await settings.getByRole('combobox', { name: 'Tela de saída' }).click();
      await page.getByRole('option', { name: /Tela 2/ }).click();
      await settings.getByRole('combobox', { name: 'Tela de descanso' }).click();
      await page.getByRole('option', { name: new RegExp(restTitle) }).click();
      await settings.getByRole('button', { name: 'Salvar' }).click();

      const config = await player.next('CONFIG');
      expect(config.displayIndex).toBe(1);
      expect(config.restScreen.kind).toBe('IMAGE');
      expect(config.restScreen.url).toMatch(/^http:\/\/localhost:8333\//);
      await expect(card).toContainText(restTitle);
      await expect(card).toContainText('Tela 2 · 1280×720');

      // Cache progress reported by the player appears on the card.
      player.send('CACHE_STATUS', {
        eventId: null,
        itemsReady: 1,
        itemsTotal: 1,
        itemsFailed: 0,
        bytesReady: 1000,
        bytesTotal: 1000,
      });
      await expect(card).toContainText('Pronta');

      // Remove: the player is revoked and the card disappears.
      await card.getByRole('button', { name: 'Remover' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Remover' }).click();
      expect((await player.next('REVOKED')).reason).toBeTruthy();
      await expect(card).toHaveCount(0);
    } finally {
      player.close();
    }

    // Clean up the library item.
    await openLibraryMedia(page);
    await mediaRow(page, restTitle).getByRole('button', { name: 'Mais ações' }).click();
    await page.getByRole('menuitem', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(mediaRow(page, restTitle)).toHaveCount(0);
  });

  test('player desconectado não pode ser aprovado', async ({ page }, testInfo) => {
    const hostname = `e2e-offline-${testInfo.project.name}-${Date.now()}`;
    const player = await FakePlayer.connect(hostname);
    await player.next('PAIRING_PENDING');
    player.close();
    cleanupAfter(player);

    await login(page, 'tecnico');
    await page.getByRole('link', { name: 'Dispositivos' }).click();
    const card = page.getByRole('article', { name: hostname });
    await expect(card).toContainText('Offline');
    await expect(card.getByRole('button', { name: 'Aprovar' })).toBeDisabled();

    await card.getByRole('button', { name: 'Recusar' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Recusar' }).click();
    await expect(card).toHaveCount(0);
  });

  test('músico e líder não veem Dispositivos', async ({ page }) => {
    await login(page, 'musico');
    // Musicians start in operation mode.
    await expect(page.getByRole('link', { name: 'Ao vivo' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Dispositivos' })).toHaveCount(0);
    // Not even in administration mode: it depends on the role, not on the mode.
    await page.getByRole('radio', { name: 'Modo administrativo' }).click();
    await expect(page.getByRole('link', { name: 'Biblioteca' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Dispositivos' })).toHaveCount(0);
  });
});
