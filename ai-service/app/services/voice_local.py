"""Local speech-to-text via faster-whisper.

The import and model load are lazy: a deployment without the optional
dependency (or without a downloaded model) still boots and can fall back to
Deepgram or the browser. The model cache lives inside ai-service/.cache so a
container layer or a dev checkout can reuse it.
"""

import asyncio
import logging
import os
import tempfile
from pathlib import Path
from typing import Optional

log = logging.getLogger(__name__)

_CACHE = Path(os.getenv(
    "WHISPER_CACHE",
    str(Path(__file__).resolve().parents[2] / ".cache" / "whisper"),
))
_model = None
_model_name = None


def _load_model():
    global _model, _model_name
    if _model is not None:
        return _model

    from faster_whisper import WhisperModel  # optional dependency

    _model_name = os.getenv("WHISPER_MODEL", "small")
    device = os.getenv("WHISPER_DEVICE", "cpu")
    compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
    _CACHE.mkdir(parents=True, exist_ok=True)
    log.info("Loading faster-whisper model %s (%s/%s)", _model_name, device, compute_type)
    _model = WhisperModel(
        _model_name,
        device=device,
        compute_type=compute_type,
        download_root=str(_CACHE),
    )
    return _model


def _run(path: str):
    model = _load_model()
    segments, info = model.transcribe(path, vad_filter=True, beam_size=1)
    collected = list(segments)
    text = "".join(segment.text for segment in collected).strip()
    # avg_logprob is negative; map it to a coarse 0-1 confidence for the UI.
    if collected:
        average = sum(getattr(segment, "avg_logprob", -1.0) for segment in collected) / len(collected)
        confidence = round(max(0.0, min(1.0, 1.0 + average)), 3)
    else:
        confidence = 0.0
    return text, info, confidence


async def transcribe(audio_data: bytes) -> Optional[dict]:
    """Returns {transcript, language, duration, provider} or None when unavailable."""
    if not audio_data:
        return None
    suffix = ".wav"
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as handle:
            handle.write(audio_data)
            path = handle.name
        text, info, confidence = await asyncio.to_thread(_run, path)
        return {
            "transcript": text,
            "language": getattr(info, "language", "en"),
            "duration": round(float(getattr(info, "duration", 0.0) or 0.0), 2),
            "provider": f"faster-whisper:{_model_name or os.getenv('WHISPER_MODEL', 'small')}",
            "confidence": confidence,
        }
    except ImportError:
        log.info("faster-whisper is not installed; falling back to the next STT provider")
        return None
    except Exception as exc:  # model download failure, bad audio, missing codec
        log.warning("Local Whisper transcription failed: %s", exc)
        return None
    finally:
        if path:
            try:
                os.unlink(path)
            except OSError:
                pass


def is_available() -> bool:
    try:
        import faster_whisper  # noqa: F401
        return True
    except ImportError:
        return False
