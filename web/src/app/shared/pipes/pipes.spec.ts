import { DurationPipe } from './duration.pipe';
import { FileSizePipe } from './file-size.pipe';

describe('DurationPipe', () => {
  const pipe = new DurationPipe();

  it('formats minutes and seconds', () => {
    expect(pipe.transform(0)).toBe('0:00');
    expect(pipe.transform(65_400)).toBe('1:05');
  });

  it('includes hours for long media', () => {
    expect(pipe.transform(3_725_000)).toBe('1:02:05');
  });

  it('is empty without a duration', () => {
    expect(pipe.transform(null)).toBe('');
  });
});

describe('FileSizePipe', () => {
  const pipe = new FileSizePipe();

  it('uses binary units with a comma decimal separator', () => {
    expect(pipe.transform(512)).toBe('512 B');
    expect(pipe.transform(1536)).toBe('1,5 KB');
    expect(pipe.transform(250 * 1024 * 1024)).toBe('250 MB');
    expect(pipe.transform(3.2 * 1024 ** 3)).toBe('3,2 GB');
  });
});
