"""Commit source sentences once, independently of transport chunk boundaries."""
import re


class SentenceAssembler:
    def __init__(self):
        self.pending = ""
        self.previous = ""
        self.start_us = 0
        self.next_id = 0
        self.previous_end_us = 0

    def push(self, text: str, start_us: int, end_us: int) -> list[dict]:
        text = text.strip()
        overlap_us = max(0, self.previous_end_us - start_us)
        self.previous_end_us = end_us
        # Only trim within the overlapping audio window; independent repeats are real speech.
        max_overlap = min(100, len(self.previous), len(text), overlap_us * 30 // 1_000_000)
        for length in range(max_overlap, 3, -1):
            if self.previous[-length:].casefold() == text[:length].casefold():
                text = text[length:].lstrip()
                break
        self.previous = (self.previous + text)[-100:]
        if not text:
            return []
        if not self.pending:
            self.start_us = start_us
        self.pending += (" " if self.pending and self.pending[-1].isascii() and text[0].isascii() else "") + text
        parts = re.split(r"(?<=[。！？])|(?<=[.!?])\s+", self.pending)
        finished = parts[:-1]
        tail = parts[-1]
        if tail.endswith(tuple(".!?。！？")) or end_us - self.start_us >= 12_000_000 or len(tail) >= 1000:
            finished.append(tail)
            tail = ""
        self.pending = tail
        result = [self._commit(part, end_us) for part in finished if part.strip()]
        if result and tail:
            self.start_us = start_us
        return result

    def flush(self, end_us: int) -> list[dict]:
        if not self.pending.strip():
            return []
        result = self._commit(self.pending, end_us)
        self.pending = ""
        return [result]

    def _commit(self, text: str, end_us: int) -> dict:
        result = {"id": f"u:{self.next_id}", "source": text.strip(),
                  "startUs": self.start_us, "endUs": end_us}
        self.next_id += 1
        return result
