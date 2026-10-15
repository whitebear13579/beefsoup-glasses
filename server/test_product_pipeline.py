import base64
from uuid import uuid4

import av
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app import create_app
from sentences import SentenceAssembler
from test_app import FakeEngine, chunk, hello
from vision import validate_jpeg, validate_review, crop_jpeg


def jpeg():
    codec = av.CodecContext.create("mjpeg", "w")
    codec.width = codec.height = 64
    codec.pix_fmt = "yuvj420p"
    frame = av.VideoFrame.from_ndarray(np.full((64, 64, 3), 240, dtype=np.uint8), format="rgb24")
    return bytes(codec.encode(frame)[0])


def test_sentence_across_chunks_overlap_and_stop_flush():
    a = SentenceAssembler()
    assert a.push("We will finish the", 0, 4_000_000) == []
    done = a.push("finish the project tomorrow. Next", 3_500_000, 7_500_000)
    assert [x["source"] for x in done] == ["We will finish the project tomorrow."]
    assert done[0]["id"] == "u:0"
    tail = a.flush(7_500_000)
    assert tail[0]["source"] == "Next" and tail[0]["id"] == "u:1"
    assert a.flush(7_500_000) == []


def test_same_sentence_outside_overlap_is_not_deleted():
    a = SentenceAssembler()
    assert a.push("Hello there.", 0, 4_000_000)[0]["id"] == "u:0"
    assert a.push("Hello there.", 4_000_000, 8_000_000)[0]["source"] == "Hello there."


@pytest.mark.parametrize("continuous,confidence,suppressed", [(True, 0.99, True), (True, 0.4, False), (False, 0.99, False)])
def test_target_language_does_not_create_a_spoken_loop_but_original_is_retained(continuous, confidence, suppressed):
    class Engine(FakeEngine):
        def transcribe_with_language(self, pcm):
            return "我們將完成專案。", "zh", confidence
        def translate(self, text):
            assert not suppressed, "Confident target-language speech must not be translated again"
            return "我們將完成專案。"
        def summarize(self, evidence):
            assert evidence[0]["text"] == "我們將完成專案。"
            return [{"text": "來源仍保留", "sourceIds": ["a:0"]}]
    sid = str(uuid4())
    with TestClient(create_app(Engine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json({**hello(sid), "continuousTranslation": continuous})
        ws.receive_json()
        ws.send_json(chunk(sid))
        result = ws.receive_json()
        assert result["type"] == "result" and result["transcript"] == "我們將完成專案。"
        if suppressed:
            assert result["utterances"] == [] and result["translation"] == ""
            assert result["speechSuppressed"] == "already_target_language"
        else:
            assert result["translation"] == "我們將完成專案。"
        ws.send_json({"type": "finalize", "sessionId": sid, "epoch": 1})
        assert "[a:0]" in ws.receive_json()["text"]


def test_continuous_translation_waits_for_sentence_and_retry_does_not_consume_it():
    class Engine(FakeEngine):
        def __init__(self):
            super().__init__()
            self.failed = False
        def transcribe(self, pcm):
            return "明天交件。"
        def translate(self, text):
            if not self.failed:
                self.failed = True
                raise RuntimeError("temporary")
            return "Submit tomorrow."
    sid = str(uuid4())
    with TestClient(create_app(Engine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json({**hello(sid), "continuousTranslation": True})
        ws.receive_json()
        ws.send_json(chunk(sid))
        assert ws.receive_json()["code"] == "inference_unavailable"
        ws.send_json(chunk(sid))
        result = ws.receive_json()
        assert result["utterances"][0]["id"] == "u:0"
        assert result["utterances"][0]["source"] == "明天交件。"
        ws.send_json(chunk(sid))
        assert ws.receive_json() == result


def test_continuous_final_tail_is_retryable_and_not_lost_on_finalize():
    sid = str(uuid4())
    with TestClient(create_app(FakeEngine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json({**hello(sid), "continuousTranslation": True})
        ws.receive_json()
        ws.send_json(chunk(sid))
        assert ws.receive_json()["utterances"] == []
        finalize = {"type": "finalize", "sessionId": sid, "epoch": 1}
        ws.send_json(finalize)
        first = ws.receive_json()
        assert first["type"] == "utterance" and first["source"] == "會議決定明天交件"
        assert ws.receive_json()["type"] == "summary"
        ws.send_json(finalize)
        assert ws.receive_json() == first
        assert ws.receive_json()["type"] == "summary"


class VisionEngine(FakeEngine):
    def __init__(self):
        super().__init__()
        self.vision_calls = 0
    def review_image(self, image, ocr, context):
        self.vision_calls += 1
        return {"keep": True, "category": "whiteboard", "uncertain": False,
                "text": "交件：明天", "reason": "白板上有會議待辦", "roi": [0.1, 0.1, 0.9, 0.8]}


def test_vision_auth_image_validation_and_identity():
    engine = VisionEngine()
    client = TestClient(create_app(engine, "secret"))
    payload = {"sessionId": str(uuid4()), "epoch": 1, "ptsUs": 500000,
               "jpegBase64": base64.b64encode(jpeg()).decode(), "ocrText": "交件", "context": "明天提交"}
    assert client.post("/vision/review", json=payload).status_code == 401
    headers = {"Authorization": "Bearer secret"}
    assert client.post("/vision/review", json={**payload, "epoch": True}, headers=headers).status_code == 422
    assert client.post("/vision/review", json={**payload, "jpegBase64": "garbage"}, headers=headers).status_code == 422
    assert engine.vision_calls == 0
    result = client.post("/vision/review", json=payload, headers=headers)
    assert result.status_code == 200
    assert result.json()["sessionId"] == payload["sessionId"]
    assert result.json()["ptsUs"] == 500000 and result.json()["keep"]
    assert engine.vision_calls == 1


def test_vision_never_promotes_phone_labels_uncertain_or_invalid_roi():
    result = VisionEngine().review_image(b"", "", "")
    for category in ("phone", "label", "other"):
        assert not validate_review({**result, "category": category})["keep"]
    assert not validate_review({**result, "uncertain": True})["keep"]
    for roi in ([0.9, 0, 0.1, 1], [-1, 0, 1, 1], [0, 0, float("nan"), 1], [True, 0, 1, 1]):
        with pytest.raises(ValueError):
            validate_review({**result, "roi": roi})
    with pytest.raises(ValueError):
        validate_jpeg(base64.b64encode(b"\xff\xd8garbage\xff\xd9").decode())


def test_roi_is_physically_cropped_before_second_ocr_pass():
    cropped = crop_jpeg(jpeg(), [0.0, 0.0, 0.5, 1.0])
    import io
    with av.open(io.BytesIO(cropped)) as container:
        frame = next(container.decode(video=0))
        assert (frame.width, frame.height) == (32, 64)
    with pytest.raises(ValueError, match="vision_region_too_small"):
        crop_jpeg(jpeg(), [0.0, 0.0, 0.01, 1.0])


def test_excluded_visual_evidence_cannot_remain_in_summary():
    class Engine(FakeEngine):
        def summarize(self, evidence):
            return [{"text": "已排除的畫面", "sourceIds": ["v:3"]}]
    sid = str(uuid4())
    with TestClient(create_app(Engine(), "secret")).websocket_connect("/ws") as ws:
        ws.send_json(hello(sid)); ws.receive_json()
        ws.send_json({"type": "ocr", "sessionId": sid, "epoch": 1, "sequence": 3, "ptsUs": 0, "text": "原始重點"})
        assert ws.receive_json()["type"] == "ocr_ack"
        ws.send_json({"type": "ocr_remove", "sessionId": sid, "epoch": 1, "sequence": 3})
        assert ws.receive_json()["type"] == "ocr_removed"
        ws.send_json({"type": "finalize", "sessionId": sid, "epoch": 1})
        assert ws.receive_json()["text"] == "尚無可引用來源的摘要。"
