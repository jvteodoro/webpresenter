import { Lyrics, Part, PartKind } from './song';

/**
 * Plain-text form of lyrics, for quick editing:
 *
 *   [Estrofe 1]
 *   first line
 *   second line          ← lines of one slide
 *                         ← blank line: next slide
 *   third line
 *
 *   [Coro]
 *   (Todos:) chorus line  ← "(cue)" at the start of a slide's first line
 *
 *   [Instrumental]
 *
 *   Ordem: Estrofe 1, Coro, Instrumental, Coro
 *
 * The kind of a part comes from its label (Coro/Refrão → CHORUS, Ponte → BRIDGE…).
 */
export function lyricsToText(lyrics: Lyrics): string {
  // Labels identify parts in the text, so they must be unique ("Coro", "Coro 2").
  const seen = new Map<string, number>();
  const label = new Map<string, string>();
  for (const part of lyrics.parts) {
    const n = (seen.get(part.label.toLowerCase()) ?? 0) + 1;
    seen.set(part.label.toLowerCase(), n);
    label.set(part.id, n === 1 ? part.label : `${part.label} ${n}`);
  }
  const blocks = lyrics.parts.map((part) => {
    const slides = part.slides
      .filter((s) => s.lines.length > 0)
      .map((s) => (s.cue ? [`(${s.cue}) ${s.lines[0] ?? ''}`, ...s.lines.slice(1)] : s.lines).join('\n'));
    const header = `[${label.get(part.id)}]`;
    return slides.length === 0 ? header : `${header}\n${slides.join('\n\n')}`;
  });
  const order = lyrics.order.map((id) => label.get(id) ?? id).join(', ');
  return `${blocks.join('\n\n')}\n\nOrdem: ${order}\n`;
}

export class LyricsTextError extends Error {}

export function textToLyrics(text: string): Lyrics {
  const parts: Part[] = [];
  let order: string[] | null = null;
  let current: Part | null = null;
  let slideLines: string[] = [];
  let cue: string | null = null;

  const flushSlide = () => {
    if (current && slideLines.length > 0) {
      current.slides.push({ lines: slideLines, cue });
    }
    slideLines = [];
    cue = null;
  };

  for (const raw of text.replace(/\r\n/g, '\n').split('\n')) {
    const line = raw.trim();
    const header = line.match(/^\[(.+)\]$/);
    const orderLine = line.match(/^ordem\s*:\s*(.*)$/i);
    if (header) {
      flushSlide();
      const label = header[1].trim();
      if (parts.some((p) => p.label.toLowerCase() === label.toLowerCase())) {
        throw new LyricsTextError(`A parte "${label}" aparece duas vezes; use a linha Ordem para repetir.`);
      }
      current = { id: `p${parts.length + 1}`, kind: kindOf(label), label, slides: [] };
      parts.push(current);
    } else if (orderLine) {
      flushSlide();
      order = orderLine[1].split(',').map((s) => s.trim()).filter(Boolean);
    } else if (line === '') {
      flushSlide();
    } else {
      if (!current) {
        throw new LyricsTextError('O texto deve começar com o nome de uma parte, por exemplo [Estrofe 1].');
      }
      const withCue = slideLines.length === 0 ? line.match(/^\((.+?)\)\s*(.*)$/) : null;
      if (withCue) {
        cue = withCue[1].trim();
        if (withCue[2]) {
          slideLines.push(withCue[2]);
        }
      } else {
        slideLines.push(line);
      }
    }
  }
  flushSlide();

  for (const part of parts) {
    if (part.slides.length === 0) {
      if (part.kind !== 'INSTRUMENTAL') {
        throw new LyricsTextError(`A parte "${part.label}" está vazia.`);
      }
      part.slides.push({ lines: [], cue: null });
    }
  }
  const byLabel = new Map(parts.map((p) => [p.label.toLowerCase(), p.id]));
  const orderIds = (order ?? parts.map((p) => p.label)).map((label) => {
    const id = byLabel.get(label.toLowerCase());
    if (!id) {
      throw new LyricsTextError(`A ordem cita "${label}", que não existe.`);
    }
    return id;
  });
  return { parts, order: orderIds };
}

function kindOf(label: string): PartKind {
  const l = label.toLowerCase();
  const rules: [RegExp, PartKind][] = [
    [/^(coro|refr[aã]o|chorus)/, 'CHORUS'],
    [/^(pr[eé]-?(coro|refr[aã]o))/, 'PRE_CHORUS'],
    [/^(ponte|bridge)/, 'BRIDGE'],
    [/^(intro)/, 'INTRO'],
    [/^(final|outro|encerramento)/, 'OUTRO'],
    [/^(instrumental|solo|interl[uú]dio)/, 'INSTRUMENTAL'],
    [/^(estrofe|verso|verse)/, 'VERSE'],
  ];
  return rules.find(([re]) => re.test(l))?.[1] ?? 'OTHER';
}
