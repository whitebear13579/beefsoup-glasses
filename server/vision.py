"""Bounded local visual review. Rejected frames are not persisted."""
import asyncio
import base64
import hmac
import io
import math
from uuid import UUID

from fastapi import HTTPException, Request
from starlette.concurrency import run_in_threadpool


def validate_jpeg(encoded: object) -> bytes:
    if not isinstance(encoded, str) or not 1 <= len(encoded) <= 700_000:
        raise ValueError("invalid_image")
    try:
        data = base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise ValueError("invalid_image") from exc
    if len(data) > 512_000 or not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
        raise ValueError("invalid_image")
    # Inspect dimensions before invoking the native image decoder.
    offset = 2
    dimensions = None
    while offset + 4 <= len(data):
        if data[offset] != 0xff:
            raise ValueError("invalid_image")
        while offset < len(data) and data[offset] == 0xff:
            offset += 1
        marker = data[offset]
        offset += 1
        if marker in (0xd9, 0xda):
            break
        if marker in range(0xd0, 0xd9) or marker == 1:
            continue
        size = int.from_bytes(data[offset:offset + 2], "big")
        if size < 2 or offset + size > len(data):
            raise ValueError("invalid_image")
        if marker in (0xc0, 0xc1, 0xc2):
            if size < 8:
                raise ValueError("invalid_image")
            height = int.from_bytes(data[offset + 3:offset + 5], "big")
            width = int.from_bytes(data[offset + 5:offset + 7], "big")
            dimensions = (width, height)
            break
        offset += size
    if dimensions is None or not all(32 <= side <= 2048 for side in dimensions) or math.prod(dimensions) > 2_000_000:
        raise ValueError("invalid_image_dimensions")
    import av
    try:
        with av.open(io.BytesIO(data)) as container:
            frame = next(container.decode(video=0))
            if (frame.width, frame.height) != dimensions:
                raise ValueError("invalid_image_dimensions")
    except Exception as exc:
        raise ValueError("invalid_image") from exc
    return data


def validate_review(value: object) -> dict:
    if not isinstance(value, dict) or type(value.get("keep")) is not bool:
        raise ValueError("invalid_vision_result")
    category = value.get("category")
    if category not in {"whiteboard", "slide", "document", "phone", "label", "other"}:
        raise ValueError("invalid_vision_category")
    roi = value.get("roi")
    if not isinstance(roi, list) or len(roi) != 4 or any(
        type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in roi
    ) or roi[0] >= roi[2] or roi[1] >= roi[3]:
        raise ValueError("invalid_vision_roi")
    if type(value.get("uncertain")) is not bool:
        raise ValueError("invalid_vision_result")
    text, reason = value.get("text"), value.get("reason")
    if not isinstance(text, str) or len(text) > 4000 or not isinstance(reason, str) or len(reason) > 500:
        raise ValueError("invalid_vision_text")
    keep = value["keep"] and category in {"whiteboard", "slide", "document"} and not value["uncertain"] and bool(text.strip())
    return {"keep": keep, "category": category, "roi": roi, "uncertain": value["uncertain"],
            "text": text.strip() if keep else "", "reason": reason.strip()}


def crop_jpeg(data: bytes, roi: list[float]) -> bytes:
    """Physically remove surrounding text before the second OCR pass."""
    import av
    with av.open(io.BytesIO(data)) as container:
        frame = next(container.decode(video=0))
        pixels = frame.to_ndarray(format="rgb24")
    height, width = pixels.shape[:2]
    left, top = int(roi[0] * width), int(roi[1] * height)
    right, bottom = math.ceil(roi[2] * width), math.ceil(roi[3] * height)
    if right - left < 32 or bottom - top < 32:
        raise ValueError("vision_region_too_small")
    cropped = av.VideoFrame.from_ndarray(pixels[top:bottom, left:right].copy(), format="rgb24")
    codec = av.CodecContext.create("mjpeg", "w")
    codec.width, codec.height = cropped.width, cropped.height
    codec.pix_fmt = "yuvj420p"
    return b"".join(bytes(packet) for packet in codec.encode(cropped))


def install_vision(app, engine, token):
    active = asyncio.Lock()

    @app.post("/vision/review")
    async def review(request: Request):
        auth = request.headers.get("authorization", "")
        if not hmac.compare_digest(auth.encode(), ("Bearer " + token).encode()):
            raise HTTPException(401, "unauthorized")
        # Read incrementally; Content-Length alone is not a reliable limit.
        body = bytearray()
        async for part in request.stream():
            body.extend(part)
            if len(body) > 750_000:
                raise HTTPException(413, "image_request_too_large")
        import json
        try:
            data = json.loads(body)
            session_id = str(UUID(data["sessionId"]))
            for key in ("epoch", "ptsUs"):
                if type(data.get(key)) is not int or data[key] < (1 if key == "epoch" else 0):
                    raise ValueError("invalid_" + key)
            for key, limit in (("ocrText", 4000), ("context", 4000)):
                if not isinstance(data.get(key), str) or len(data[key]) > limit:
                    raise ValueError("invalid_" + key)
            jpeg = validate_jpeg(data.get("jpegBase64"))
        except (ValueError, TypeError, KeyError, IndexError) as exc:
            raise HTTPException(422, str(exc)[:80]) from exc
        if active.locked():
            raise HTTPException(429, "vision_busy_audio_continues")
        async with active:
            try:
                result = await run_in_threadpool(engine.review_image, jpeg, data["ocrText"], data["context"])
                result = validate_review(result)
            except (ValueError, TypeError) as exc:
                raise HTTPException(502, "invalid_vision_result") from exc
            except Exception as exc:
                raise HTTPException(503, "vision_unavailable") from exc
        return {**result, "sessionId": session_id, "epoch": data["epoch"], "ptsUs": data["ptsUs"],
                "model": getattr(engine, "vision_model", type(engine).__name__)}
