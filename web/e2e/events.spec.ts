import { Locator, Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FakePlayer, approveDevice, deleteDevice, deleteTestData } from './fake-player';
import { FIXTURES_DIR, login, mediaRow, openLibraryMedia } from './fixtures';

/**
 * CDK drag and drop reacts to real pointer movement: press, move past the drag threshold, glide
 * to the target in small steps, rest there, then release.
 */
async function dragTo(page: Page, handle: Locator, target: Locator): Promise<void> {
  await handle.scrollIntoViewIfNeeded();
  const from = (await handle.boundingBox())!;
  const to = (await target.boundingBox())!;
  const x = from.x + from.width / 2;
  const startY = from.y + from.height / 2;
  const endY = to.y + to.height / 4; // upper half of the target: the item lands before it
  await page.mouse.move(x, startY);
  await page.mouse.down();
  await page.mouse.move(x, startY - 8, { steps: 4 });
  await page.waitForTimeout(100);
  await page.mouse.move(x, endY, { steps: 20 });
  await page.waitForTimeout(200);
  await page.mouse.up();
}

async function uploadToLibrary(
  page: Page,
  title: string,
  file: string,
  mimeType: string,
): Promise<void> {
  await page.locator('input[type=file]').setInputFiles({
    name: `${title}.${file.split('.').pop()}`,
    mimeType,
    buffer: readFileSync(join(FIXTURES_DIR, file)),
  });
  await expect(mediaRow(page, title)).toBeVisible();
}

async function deleteFromLibrary(page: Page, title: string): Promise<void> {
  await mediaRow(page, title).getByRole('button', { name: 'Mais ações' }).click();
  await page.getByRole('menuitem', { name: 'Excluir' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
}

test.describe('Eventos', () => {
  test('monta um evento, prepara os players e acompanha a prontidão', async ({
    page,
  }, testInfo) => {
    const tag = `${testInfo.project.name} ${Date.now()}`;
    const video = `E2E abertura ${tag}`;
    const image = `E2E fundo ${tag}`;
    const eventTitle = `E2E culto ${tag}`;
    const playerName = `E2E telão ${tag}`;
    const player = await FakePlayer.connect(`e2e-events-${tag}`);
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, playerName);
      await player.next('CONFIG');
      await player.next('PREPARE'); // whatever is current right now

      await login(page, 'tecnico');
      await uploadToLibrary(page, video, 'clip.mp4', 'video/mp4');
      await uploadToLibrary(page, image, 'slide.png', 'image/png');

      // Create the event.
      await page.getByRole('link', { name: 'Eventos' }).click();
      await page.getByRole('button', { name: 'Novo evento' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Título').fill(eventTitle);
      await dialog.getByLabel('Data e hora').fill('2026-11-22T18:00');
      await dialog.getByRole('button', { name: 'Salvar' }).click();
      await expect(page.getByRole('heading', { level: 1, name: eventTitle })).toBeVisible();
      await expect(page.getByText('Domingo, 22 de novembro às 18:00')).toBeVisible();

      // Add two media at once (in the order checked) and a slide.
      await page.getByRole('button', { name: 'Adicionar mídia' }).click();
      const picker = page.getByRole('dialog');
      await picker.getByLabel('Buscar na biblioteca').fill(tag);
      await picker.getByRole('checkbox', { name: new RegExp(image) }).check();
      await picker.getByRole('checkbox', { name: new RegExp(video) }).check();
      await picker.getByRole('button', { name: /Adicionar 2/ }).click();
      await page.getByRole('button', { name: 'Adicionar slide' }).click();
      const slideDialog = page.getByRole('dialog');
      await slideDialog.getByLabel('Título').fill('Avisos');
      await slideDialog.getByLabel('Texto').fill('Culto de oração\nquarta-feira, 19h30');
      await slideDialog.getByRole('button', { name: 'Salvar' }).click();

      const items = page.locator('li.item');
      await expect(items).toHaveCount(3);
      await expect(items.nth(0)).toContainText(image);
      await expect(items.nth(1)).toContainText(video);
      await expect(items.nth(2)).toContainText('Avisos');
      await expect(page.getByText('0:03 de mídia')).toBeVisible();

      // Reorder by dragging the slide to the top; it survives a reload.
      await dragTo(page, items.nth(2).getByLabel('Arrastar para reordenar'), items.nth(0));
      await expect(items.nth(0)).toContainText('Avisos');
      await page.reload();
      await expect(page.locator('li.item').nth(0)).toContainText('Avisos');

      // Edit the slide and remove the image.
      await page
        .locator('li.item', { hasText: 'Avisos' })
        .getByRole('button', { name: 'Editar slide' })
        .click();
      await page.getByRole('dialog').getByLabel('Título').fill('Avisos da semana');
      await page.getByRole('dialog').getByRole('button', { name: 'Salvar' }).click();
      await expect(page.locator('li.item').nth(0)).toContainText('Avisos da semana');
      await page
        .locator('li.item', { hasText: image })
        .getByRole('button', { name: 'Remover item' })
        .click();
      await expect(page.locator('li.item')).toHaveCount(2);

      // Prepare: the player receives the plan, reports progress, and shows as ready.
      await page.getByRole('button', { name: 'Preparar nos players' }).click();
      const plan = await player.nextPrepareFor(eventTitle);
      expect(plan.title).toBe(eventTitle);
      expect(plan.items.map((i: { type: string }) => i.type)).toEqual(['SLIDE', 'MEDIA']);
      expect(plan.items[1].media.sha256).toHaveLength(64);

      const row = page.getByRole('listitem', { name: playerName });
      player.send('CACHE_STATUS', {
        eventId: plan.eventId,
        itemsReady: 0,
        itemsTotal: 1,
        itemsFailed: 0,
        bytesReady: 1024,
        bytesTotal: 4096,
      });
      await expect(row).toContainText('Baixando 1 KB de 4 KB');
      player.send('CACHE_STATUS', {
        eventId: plan.eventId,
        itemsReady: 1,
        itemsTotal: 1,
        itemsFailed: 0,
        bytesReady: 4096,
        bytesTotal: 4096,
      });
      await expect(row).toContainText('Pronto');

      // The event list shows it as the current event.
      await page.getByRole('link', { name: 'Voltar aos eventos' }).click();
      await expect(page.getByRole('link', { name: new RegExp(eventTitle) })).toContainText(
        'Evento atual',
      );

      // Media in use cannot be deleted from the library.
      await openLibraryMedia(page);
      await deleteFromLibrary(page, video);
      await expect(
        page.getByText(`"${video}" está em uso no evento "${eventTitle}"`),
      ).toBeVisible();

      // Deleting the current event clears the players' plan.
      await page.getByRole('link', { name: 'Eventos' }).click();
      await page.getByRole('link', { name: new RegExp(eventTitle) }).click();
      await page.getByRole('button', { name: 'Opções do evento' }).click();
      await page.getByRole('menuitem', { name: 'Excluir evento' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();
      expect((await player.next('PREPARE')).eventId).toBeNull();

      await openLibraryMedia(page);
      await deleteFromLibrary(page, video);
      await expect(mediaRow(page, video)).toHaveCount(0);
      await deleteFromLibrary(page, image);
      await expect(mediaRow(page, image)).toHaveCount(0);
    } finally {
      player.close();
      await deleteDevice(player.deviceId);
      await deleteTestData(tag);
    }
  });

  test('líder cria eventos; músico só consulta', async ({ browser }, testInfo) => {
    const title = `E2E ensaio ${testInfo.project.name} ${Date.now()}`;
    const leaderContext = await browser.newContext();
    const musicianContext = await browser.newContext();
    try {
      const leader = await leaderContext.newPage();
      await login(leader, 'lider', '/eventos');
      await leader.getByRole('button', { name: 'Novo evento' }).click();
      await leader.getByRole('dialog').getByLabel('Título').fill(title);
      await leader.getByRole('dialog').getByRole('button', { name: 'Salvar' }).click();
      await expect(
        leader.getByRole('heading', { level: 1, name: title, exact: false }),
      ).toBeVisible();
      await leader.getByRole('button', { name: 'Adicionar slide' }).click();
      await leader.getByRole('dialog').getByLabel('Título').fill('Oração');
      await leader.getByRole('dialog').getByRole('button', { name: 'Salvar' }).click();
      await expect(leader.locator('li.item')).toHaveCount(1);
      const url = leader.url();

      const musician = await musicianContext.newPage();
      await login(musician, 'musico', '/eventos');
      await expect(musician.getByRole('button', { name: 'Novo evento' })).toHaveCount(0);
      await musician.goto(url);
      await expect(musician.getByRole('heading', { level: 1, name: title })).toBeVisible();
      await expect(musician.locator('li.item')).toContainText('Oração');
      await expect(musician.getByRole('button', { name: 'Adicionar slide' })).toHaveCount(0);
      await expect(musician.getByRole('button', { name: 'Preparar nos players' })).toHaveCount(0);
      await expect(musician.getByLabel('Arrastar para reordenar')).toHaveCount(0);

      await leader.getByRole('button', { name: 'Opções do evento' }).click();
      await leader.getByRole('menuitem', { name: 'Excluir evento' }).click();
      await leader.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
      await expect(leader.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();
    } finally {
      await leaderContext.close();
      await musicianContext.close();
      await deleteTestData(title);
    }
  });
});
