/** One Server-Sent Event: its name ("message" when the server gave none) and its data. */
export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Minimal SSE parser over a fetch body (fetch, unlike EventSource, can send the access token):
 * events are separated by blank lines; "event:" names them and "data:" lines are joined.
 */
export async function readSse(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
  onEvent: (event: SseEvent) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) {
      return;
    }
    buffer += decoder.decode(value, { stream: true });
    let boundary: number;
    while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''));
        }
      }
      if (data.length) {
        onEvent({ event, data: data.join('\n') });
      }
    }
  }
}
