import asyncio

from app.services import voice_local
from app.services.voice_service import VoiceService


def run(coro):
    return asyncio.run(coro)


def test_local_whisper_is_first_in_the_chain(monkeypatch):
    async def fake(audio):
        return {
            "transcript": "local transcript",
            "language": "en",
            "duration": 3.0,
            "provider": "faster-whisper:base",
            "confidence": 0.0,
        }

    monkeypatch.setattr(voice_local, "transcribe", fake)
    service = VoiceService()
    service.provider = "auto"
    service.deepgram_api_key = ""

    result = run(service.transcribe(b"audio"))

    assert result["provider"] == "faster-whisper:base"
    assert result["transcript"] == "local transcript"


def test_deepgram_is_used_when_local_is_unavailable(monkeypatch):
    async def no_local(audio):
        return None

    async def deepgram(audio):
        return {
            "transcript": "deepgram transcript",
            "language": "en",
            "duration": 4.0,
            "provider": "deepgram",
            "confidence": 0.91,
        }

    monkeypatch.setattr(voice_local, "transcribe", no_local)
    service = VoiceService()
    service.provider = "auto"
    service.deepgram_api_key = "key"
    monkeypatch.setattr(service, "_transcribe_deepgram", deepgram)

    result = run(service.transcribe(b"audio"))

    assert result["provider"] == "deepgram"
    assert result["confidence"] == 0.91


def test_unavailable_providers_are_labelled_not_faked(monkeypatch):
    async def no_local(audio):
        return None

    monkeypatch.setattr(voice_local, "transcribe", no_local)
    service = VoiceService()
    service.provider = "local"
    service.deepgram_api_key = ""

    result = run(service.transcribe(b"audio"))

    assert result["provider"] == "local-whisper"
    assert result["transcript"] == ""
    assert "error" in result


def test_tts_falls_back_to_empty_audio_when_no_engine(monkeypatch):
    async def no_edge(text, voice, rate):
        return b""

    async def no_piper(text):
        return b""

    service = VoiceService()
    service.tts_provider = "auto"
    service.gemini_api_key = ""
    monkeypatch.setattr(service, "_synthesize_edge", no_edge)
    monkeypatch.setattr(service, "_synthesize_piper", no_piper)

    assert run(service.synthesize("hello")) == b""
