import { expect, test } from '@playwright/test';
import { api } from './fake-player';
import { login } from './fixtures';

const DAY_LABELS = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado'];

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

test.describe('Liturgia e calendário', () => {
  test('modelo do dia, evento criado pelo calendário e espaço preenchido', async ({ page }, testInfo) => {
    const tag = `E2E liturgia ${testInfo.project.name} ${Date.now()}`;
    const today = new Date();
    const song = (await api('POST', '/api/songs', {
      title: `${tag} hino`,
      lyrics: { parts: [{ id: 'v', kind: 'VERSE', label: 'E', slides: [{ lines: ['Santo, santo'] }] }], order: ['v'] },
    }, 'tecnico')).id;
    let templateId: string | null = null;
    let eventId: string | null = null;
    try {
      await login(page, 'lider', '/eventos');
      await page.getByRole('radio', { name: 'Modo administrativo' }).click();
      await page.getByRole('link', { name: 'Liturgia' }).click();
      await page.getByRole('button', { name: 'Novo modelo' }).click();
      await page.getByRole('dialog').getByLabel('Nome').fill(tag);
      await page.getByRole('dialog').getByRole('button', { name: 'Salvar' }).click();
      await expect(page.getByLabel('Nome do modelo')).toHaveValue(tag);
      templateId = page.url().split('/').pop()!;

      // Today's weekday and the start time.
      await page.getByRole('button', { name: DAY_LABELS[today.getDay()], exact: true }).click();
      await page.getByLabel('Início').fill('09:30');
      await page.getByLabel('Início').blur();

      // A slot chosen each week, then a fixed slide.
      await page.getByRole('button', { name: 'Espaço a escolher' }).click();
      let dialog = page.getByRole('dialog');
      await dialog.getByLabel('Nome do espaço').fill('Hino inicial');
      await dialog.getByRole('radio', { name: 'Uma música' }).check();
      await dialog.getByRole('button', { name: 'Adicionar' }).click();
      await page.getByRole('button', { name: 'Item fixo' }).click();
      await page.getByRole('menuitem', { name: 'Slide' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByLabel('Título').fill('Oração');
      await dialog.getByRole('button', { name: /Salvar|Adicionar/ }).click();
      const slots = page.getByRole('list', { name: 'Itens do modelo' }).getByRole('listitem');
      await expect(slots).toHaveCount(2);
      await expect(slots.nth(0)).toContainText('A escolher a cada evento');

      // The calendar shows the expected event today; creating it opens the event.
      await page.getByRole('link', { name: 'Calendário' }).click();
      await page.getByRole('button', { name: `Criar ${tag} em ${dayKey(today)}` }).click();
      await expect(page.getByRole('heading', { level: 1 })).toContainText(tag);
      eventId = page.url().split('/').pop()!;
      await expect(page.getByRole('status').filter({ hasText: 'a preencher' })).toContainText('1 espaço da liturgia a preencher');
      await expect(page.locator('.meta')).toContainText('09:30');

      // Fill the slot with this week's hymn.
      await page.getByRole('button', { name: 'Escolher Hino inicial' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByLabel('Buscar por título ou letra').fill(`${tag} hino`);
      await dialog.getByRole('button', { name: new RegExp(`${tag} hino`) }).click();
      await dialog.getByRole('button', { name: 'Adicionar' }).click();
      await expect(page.getByRole('listitem', { name: `${tag} hino` })).toBeVisible();
      await expect(page.getByRole('status').filter({ hasText: 'a preencher' })).toHaveCount(0);

      // Back in the calendar: the event replaces the expected one.
      await page.getByRole('link', { name: 'Calendário' }).click();
      const cell = page.getByRole('gridcell', { name: dayKey(today) });
      await expect(cell.getByRole('link', { name: new RegExp(tag) })).toBeVisible();
      await expect(cell.getByRole('button', { name: `Criar ${tag} em ${dayKey(today)}` })).toHaveCount(0);
    } finally {
      if (eventId) await api('DELETE', `/api/events/${eventId}`).catch(() => undefined);
      if (templateId) await api('DELETE', `/api/liturgy/templates/${templateId}`).catch(() => undefined);
      await api('DELETE', `/api/songs/${song}`, undefined, 'tecnico').catch(() => undefined);
    }
  });
});
