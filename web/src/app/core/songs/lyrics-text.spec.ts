import { lyricsToText, LyricsTextError, textToLyrics } from './lyrics-text';

describe('lyrics text format', () => {
  const text = `[Estrofe 1]
Castelo forte é nosso Deus
Refúgio e fortaleza

Bom escudo e boa espada

[Coro]
(Todos:) Com força nos protege

[Instrumental]

Ordem: Estrofe 1, Coro, Instrumental, Coro
`;

  it('parses parts, slides, cues and the singing order', () => {
    const lyrics = textToLyrics(text);

    expect(lyrics.parts.map((p) => [p.label, p.kind, p.slides.length])).toEqual([
      ['Estrofe 1', 'VERSE', 2],
      ['Coro', 'CHORUS', 1],
      ['Instrumental', 'INSTRUMENTAL', 1],
    ]);
    expect(lyrics.parts[0].slides[0].lines).toEqual(['Castelo forte é nosso Deus', 'Refúgio e fortaleza']);
    expect(lyrics.parts[1].slides[0]).toEqual({ lines: ['Com força nos protege'], cue: 'Todos:' });
    expect(lyrics.order).toEqual(['p1', 'p2', 'p3', 'p2']);
  });

  it('round-trips through text', () => {
    const lyrics = textToLyrics(text);
    expect(textToLyrics(lyricsToText(lyrics))).toEqual(lyrics);
  });

  it('uses the parts in written order when there is no Ordem line', () => {
    expect(textToLyrics('[A]\nx\n\n[B]\ny').order).toEqual(['p1', 'p2']);
  });

  it('explains mistakes', () => {
    expect(() => textToLyrics('linha solta')).toThrow(LyricsTextError);
    expect(() => textToLyrics('[A]\nx\nOrdem: A, B')).toThrow(/"B"/);
    expect(() => textToLyrics('[A]\nx\n\n[A]\ny')).toThrow(/duas vezes/);
  });
});
