import base64
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app import create_app, decode_audio, verified_summary


class FakeEngine:
    def __init__(self):
        self.calls = 0

    def transcribe(self, pcm16):
        self.calls += 1
        return "會議決定明天交件"

    def translate(self, text):
        return "Submit tomorrow"

    def summarize(self, evidence):
        return [
            {"text": "明天交件", "sourceIds": ["a:0"]},
            {"text": "捏造結論", "sourceIds": ["a:999"]},
        ]


def hello(session_id, token="secret", epoch=1):
    return {"type": "hello", "sessionId": session_id, "epoch": epoch, "token": token}


def chunk(session_id, sequence=0, epoch=1, pcm=b"\0\0" * 16000):
    return {"type": "audio_chunk", "sessionId": session_id, "epoch": epoch,
            "sequence": sequence, "startUs": sequence * 1_000_000, "sampleRate": 16000,
            "pcm16": base64.b64encode(pcm).decode()}


def test_api_idempotency_conflict_and_source_checked_summary():
    engine = FakeEngine()
    session_id = str(uuid4())
    with TestClient(create_app(engine, "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        assert ws.receive_json() == {"type": "ready", "sessionId": session_id, "epoch": 1}
        payload = chunk(session_id)
        ws.send_json(payload)
        result = ws.receive_json()
        assert result["transcript"] == "會議決定明天交件"
        assert result["translation"] == "Submit tomorrow"
        ws.send_json(payload)
        assert ws.receive_json() == result
        assert engine.calls == 1
        altered = chunk(session_id, pcm=b"\1\0" * 16000)
        ws.send_json(altered)
        assert ws.receive_json()["code"] == "sequence_conflict"
        ws.send_json({"type": "finalize", "sessionId": session_id, "epoch": 1})
        summary = ws.receive_json()
        assert summary["text"] == "明天交件 [a:0]"
        ws.send_json(payload)
        assert ws.receive_json() == result
        assert engine.calls == 1


def test_wrong_session_and_bad_audio_rejected_without_invoking_model():
    engine = FakeEngine()
    session_id = str(uuid4())
    with TestClient(create_app(engine, "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        wrong = chunk(str(uuid4()))
        ws.send_json(wrong)
        assert ws.receive_json()["code"] == "wrong_session"
        bad = chunk(session_id)
        bad["pcm16"] = "!!!"
        ws.send_json(bad)
        assert ws.receive_json()["code"] == "invalid_audio"
        bad = chunk(session_id)
        bad["sampleRate"] = 44100
        ws.send_json(bad)
        assert ws.receive_json()["code"] == "invalid_sample_rate"
        assert engine.calls == 0


def test_token_required_and_ocr_source_validation():
    session_id = str(uuid4())
    with TestClient(create_app(FakeEngine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id, token="bad"))
        with pytest.raises(Exception):
            ws.receive_json()
    with TestClient(create_app(FakeEngine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        ws.send_json({"type": "ocr", "sessionId": session_id, "epoch": 1,
                      "sequence": 3, "ptsUs": 500000, "text": "白板標題"})
        assert ws.receive_json()["type"] == "ocr_ack"
        ws.send_json({"type": "ocr", "sessionId": session_id, "epoch": 1,
                      "sequence": 4, "ptsUs": -1, "text": "錯誤"})
        assert ws.receive_json()["code"] == "invalid_pts_us"


def test_unit_validation_rejects_bool_sequence_and_unverified_claim():
    bad = chunk(str(uuid4()))
    bad["sequence"] = True
    with pytest.raises(ValueError, match="invalid_sequence"):
        decode_audio(bad)
    assert verified_summary([{"text": "捏造", "sourceIds": ["a:1"]}], {}) == "尚無可引用來源的摘要。"


def test_long_meeting_summarizes_first_and_last_evidence_batches():
    class BatchEngine(FakeEngine):
        def __init__(self):
            super().__init__()
            self.batches = 0

        def summarize(self, evidence):
            self.batches += 1
            return [{"text": evidence[0]["text"], "sourceIds": [evidence[0]["id"]]}]

    engine = BatchEngine()
    session_id = str(uuid4())
    with TestClient(create_app(engine, "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        for sequence in range(161):
            ws.send_json(chunk(session_id, sequence))
            assert ws.receive_json()["sequence"] == sequence
        ws.send_json({"type": "finalize", "sessionId": session_id, "epoch": 1})
        summary = ws.receive_json()["text"]
        assert engine.batches == 3
        assert "[a:0]" in summary
        assert "[a:160]" in summary


def test_model_failure_keeps_chunk_retryable():
    class FlakyEngine(FakeEngine):
        def transcribe(self, pcm16):
            self.calls += 1
            if self.calls == 1:
                raise RuntimeError("temporary model failure")
            return "可重試"

    engine = FlakyEngine()
    session_id = str(uuid4())
    with TestClient(create_app(engine, "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        payload = chunk(session_id)
        ws.send_json(payload)
        assert ws.receive_json()["code"] == "inference_unavailable"
        ws.send_json(payload)
        assert ws.receive_json()["transcript"] == "可重試"
        assert engine.calls == 2


def test_late_chunk_after_finalize_updates_summary_on_retry():
    session_id = str(uuid4())
    with TestClient(create_app(FakeEngine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        finalize = {"type": "finalize", "sessionId": session_id, "epoch": 1}
        ws.send_json(finalize)
        assert "尚無" in ws.receive_json()["text"]
        ws.send_json(chunk(session_id))
        assert ws.receive_json()["type"] == "result"
        ws.send_json(finalize)
        assert "[a:0]" in ws.receive_json()["text"]


def test_phone_can_rebuild_summary_evidence_after_pc_restart():
    session_id = str(uuid4())
    with TestClient(create_app(FakeEngine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        ws.send_json(chunk(session_id))
        assert ws.receive_json()["transcript"] == "會議決定明天交件"
    restarted_engine = FakeEngine()
    with TestClient(create_app(restarted_engine, "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(session_id))
        ws.receive_json()
        ws.send_json({"type": "transcript_evidence", "sessionId": session_id, "epoch": 1,
                      "sequence": 0, "startUs": 0, "text": "會議決定明天交件"})
        assert ws.receive_json()["type"] == "evidence_ack"
        ws.send_json({"type": "finalize", "sessionId": session_id, "epoch": 1})
        assert "[a:0]" in ws.receive_json()["text"]
        assert restarted_engine.calls == 0
