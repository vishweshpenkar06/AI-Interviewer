"""Privacy guarantees at the AI service boundary.

The service must not echo raw audio, must not accept empty/oversized payloads,
and must label the provider that produced a transcript.
"""

from fastapi.testclient import TestClient

from app.routers import ai_router
from main import app

client = TestClient(app)


def test_transcribe_returns_metadata_without_audio(monkeypatch):
    async def fake(audio_data):
        assert audio_data == b"fake-webm"
        return {
            "transcript": "I used a composite index.",
            "language": "en",
            "duration": 12.5,
            "provider": "faster-whisper:small",
            "confidence": 0.0,
        }

    monkeypatch.setattr(ai_router.voice_service, "transcribe", fake)
    response = client.post(
        "/ai/transcribe",
        files={"audio": ("clip.webm", b"fake-webm", "audio/webm")},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["transcript"] == "I used a composite index."
    assert body["provider"] == "faster-whisper:small"
    assert body["duration"] == 12.5
    # No raw audio or recording URL is returned to the client.
    assert "audio" not in body and "url" not in body and "path" not in body


def test_empty_audio_is_rejected():
    response = client.post(
        "/ai/transcribe",
        files={"audio": ("clip.webm", b"", "audio/webm")},
    )
    assert response.status_code == 400


def test_oversized_audio_is_rejected():
    response = client.post(
        "/ai/transcribe",
        files={"audio": ("clip.webm", b"x" * (25 * 1024 * 1024 + 1), "audio/webm")},
    )
    assert response.status_code == 413


def test_unavailable_provider_is_labelled(monkeypatch):
    from app.services import voice_local

    async def no_local(audio):
        return None

    monkeypatch.setattr(voice_local, "transcribe", no_local)
    monkeypatch.setattr(ai_router.voice_service, "deepgram_api_key", "")

    response = client.post(
        "/ai/transcribe",
        files={"audio": ("clip.webm", b"tiny", "audio/webm")},
    )
    # No provider is configured; the mock must be labelled, never presented as real.
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "mock"
    assert body["transcript"]
