import type { SessionClient } from "./index.js";

/** Best-effort text only. This lane never retries or aborts the execution lane. */
export class TextPreview {
  private readonly stop = new AbortController();
  private readonly signal: AbortSignal;
  private readonly opened: Promise<void>;
  private sending?: Promise<void>;
  private readonly pending: string[] = [];
  private bytes = 0;
  private sequence = 0;

  constructor(
    private readonly client: SessionClient,
    private readonly identity: object,
    private readonly modelCallId: string,
    private readonly messageId: string,
    signal: AbortSignal,
  ) {
    this.signal = AbortSignal.any([signal, this.stop.signal]);
    this.opened = this.client.post<{ interested: boolean }>(
      "previews/interest", { ...identity, modelCallId },
      AbortSignal.any([this.signal, AbortSignal.timeout(250)]),
    ).then((result) => { if (!result.interested) this.close(); })
      .catch(() => this.close());
  }

  accept(text: string): void {
    if (this.signal.aborted || !text) return;
    if (this.bytes + Buffer.byteLength(text) > 64 * 1024) {
      this.close();
      return;
    }
    const characters = Array.from(text);
    if (characters.some((value) => value.length === 1 && /[\uD800-\uDFFF]/u.test(value))) {
      this.close();
      return;
    }
    for (let offset = 0; offset < characters.length; offset += 1024) {
      const chunk = characters.slice(offset, offset + 1024).join("");
      this.bytes += Buffer.byteLength(chunk);
      if (this.pending.length >= 256) { this.close(); return; }
      this.pending.push(chunk);
    }
    this.pump();
  }

  private pump(): void {
    if (this.sending || this.signal.aborted) return;
    this.sending = (async () => {
      await this.opened;
      while (this.pending.length && !this.signal.aborted) {
        // Eight maximally JSON-escaped 1024-codepoint chunks fit the service's
        // 64 KiB request limit, including the fenced identity envelope.
        const chunks = this.pending.splice(0, 8);
        this.bytes -= chunks.reduce((sum, chunk) => sum + Buffer.byteLength(chunk), 0);
        const result = await this.client.post<{ accepted: boolean }>(
          "previews/publish",
          { ...this.identity, modelCallId: this.modelCallId, messageId: this.messageId,
            sequence: String(this.sequence), chunks },
          AbortSignal.any([this.signal, AbortSignal.timeout(250)]),
        );
        if (!result.accepted) { this.close(); return; }
        this.sequence += chunks.length + (this.sequence === 0 ? 1 : 0);
      }
    })().catch(() => this.close()).finally(() => {
      this.sending = undefined;
      if (this.pending.length && !this.signal.aborted) this.pump();
    });
  }

  async finish(): Promise<void> {
    this.pump();
    let timer: ReturnType<typeof setTimeout> | undefined;
    await Promise.race([
      this.sending,
      new Promise<void>((resolve) => { timer = setTimeout(resolve, 200); }),
    ]);
    if (timer) clearTimeout(timer);
    this.close();
  }

  close(): void {
    this.stop.abort();
    this.pending.length = 0;
    this.bytes = 0;
  }
}
