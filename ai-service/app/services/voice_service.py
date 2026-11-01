import asyncio
import hashlib
import io
import logging
import os
from typing import Dict, List, Optional

from app.services import voice_local

log = logging.getLogger(__name__)


class VoiceService:
    """Provider chain: faster-whisper → Deepgram (if key) for STT;
    edge-tts → Piper (offline) → optional Gemini for TTS."""

    def __init__(self):
        self.provider = os.getenv("STT_PROVIDER", "auto").strip().lower()
        self.tts_provider = os.getenv("TTS_PROVIDER", "edge").strip().lower()
        self.deepgram_api_key = os.getenv("DEEPGRAM_API_KEY", "")
        self.elevenlabs_api_key = os.getenv("ELEVENLABS_API_KEY", "")
        self.gemini_api_key = os.getenv(
            "GEMINI_API_KEY", os.getenv("GOOGLE_API_KEY", os.getenv("LLM_API_KEY", "")))
        # In-memory cache: text_hash -> audio_bytes (avoids re-synthesizing same text)
        self._cache: Dict[str, bytes] = {}
        self._cache_max = 200

    def _cache_key(self, text: str, voice: str) -> str:
        return hashlib.md5(f"{text}:{voice}".encode()).hexdigest()

    async def transcribe(self, audio_data: bytes) -> dict:
        """Returns transcript, language, duration, provider and confidence."""
        if self.provider in {"auto", "local", "whisper"}:
            local = await voice_local.transcribe(audio_data)
            if local and local.get("transcript"):
                return local
            if self.provider in {"local", "whisper"}:
                return self._unavailable("local-whisper")

        if self.provider in {"auto", "deepgram"} and self.deepgram_api_key:
            deepgram = await self._transcribe_deepgram(audio_data)
            if deepgram and deepgram.get("transcript"):
                return deepgram
            if self.provider == "deepgram":
                return self._unavailable("deepgram")

        if self.provider == "mock":
            return self._mock_transcribe()

        # No provider is configured. Return a clearly labelled mock rather than
        # silently pretending a real transcription happened.
        return self._mock_transcribe()

    async def synthesize(self, text: str, voice: str = "en-US-AriaNeural", rate: float = 0.9) -> bytes:
        key = self._cache_key(text, voice)
        if key in self._cache:
            return self._cache[key]

        if self.tts_provider in {"auto", "edge"}:
            result = await self._synthesize_edge(text, voice, rate)
            if result and len(result) > 100:
                self._cache_put(key, result)
                return result

        if self.tts_provider in {"auto", "piper"}:
            result = await self._synthesize_piper(text)
            if result and len(result) > 100:
                self._cache_put(key, result)
                return result

        if self.gemini_api_key:
            result = await self._synthesize_gemini(text)
            if result and len(result) > 100:
                self._cache_put(key, result)
                return result

        return self._mock_synthesize()

    def _cache_put(self, key: str, value: bytes):
        if len(self._cache) >= self._cache_max:
            oldest = next(iter(self._cache))
            del self._cache[oldest]
        self._cache[key] = value

    def _mock_transcribe(self) -> dict:
        return {
            "transcript": "This is a mock transcription of the candidate's response.",
            "language": "en",
            "duration": 0.0,
            "provider": "mock",
            "confidence": 0.0,
        }

    def _unavailable(self, provider: str) -> dict:
        return {
            "transcript": "",
            "language": "en",
            "duration": 0.0,
            "provider": provider,
            "confidence": 0.0,
            "error": f"{provider} is not available on this deployment",
        }

    def _mock_synthesize(self) -> bytes:
        return b""

    async def _transcribe_deepgram(self, audio_data: bytes) -> Optional[dict]:
        try:
            import httpx
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    "https://api.deepgram.com/v1/listen",
                    headers={
                        "Authorization": f"Token {self.deepgram_api_key}",
                        "Content-Type": "audio/webm",
                    },
                    content=audio_data,
                    params={"model": "nova-2", "language": "en", "smart_format": "true"},
                    timeout=30.0,
                )
                if response.status_code == 200:
                    result = response.json()
                    channel = result["results"]["channels"][0]
                    best = channel["alternatives"][0]
                    return {
                        "transcript": best.get("transcript", ""),
                        "language": best.get("language", "en"),
                        "duration": result.get("metadata", {}).get("duration", 0.0),
                        "provider": "deepgram",
                        "confidence": best.get("confidence", 0.0),
                    }
        except Exception as exc:
            log.warning("Deepgram transcription error: %s", exc)
        return None

    async def _synthesize_gemini(self, text: str) -> bytes:
        """Optional quality upgrade; returns empty on rate limit."""
        try:
            from google import genai
            from google.genai import types

            client = genai.Client(api_key=self.gemini_api_key)
            response = client.models.generate_content(
                model="gemini-2.5-flash-preview-tts",
                contents=text,
                config=types.GenerateContentConfig(
                    response_modalities=["AUDIO"],
                    speech_config=types.SpeechConfig(
                        voice_config=types.VoiceConfig(
                            prebuilt_voice_config=types.PrebuiltVoiceConfig(
                                voice_name="Puck"
                            )
                        )
                    ),
                ),
            )
            if response.candidates:
                for part in response.candidates[0].content.parts:
                    if hasattr(part, "inline_data") and part.inline_data:
                        return self._pcm_to_wav(part.inline_data.data)
                    if hasattr(part, "audio") and part.audio:
                        return self._pcm_to_wav(part.audio)
        except Exception as exc:
            log.info("Gemini TTS unavailable: %s", exc)
        return b""

    def _pcm_to_wav(self, pcm_data: bytes, sample_rate: int = 24000,
                    channels: int = 1, sample_width: int = 2) -> bytes:
        import struct

        data_size = len(pcm_data)
        output = io.BytesIO()
        output.write(b"RIFF")
        output.write(struct.pack("<I", 36 + data_size))
        output.write(b"WAVE")
        output.write(b"fmt ")
        output.write(struct.pack("<I", 16))
        output.write(struct.pack("<H", 1))
        output.write(struct.pack("<H", channels))
        output.write(struct.pack("<I", sample_rate))
        output.write(struct.pack("<I", sample_rate * channels * sample_width))
        output.write(struct.pack("<H", channels * sample_width))
        output.write(struct.pack("<H", sample_width * 8))
        output.write(b"data")
        output.write(struct.pack("<I", data_size))
        output.write(pcm_data)
        return output.getvalue()

    async def _synthesize_edge(self, text: str, voice: str, rate: float) -> bytes:
        try:
            import edge_tts

            adjusted_rate = max(0.85, min(rate, 1.1))
            rate_pct = int((adjusted_rate - 1) * 100)
            rate_str = f"+{rate_pct}%" if rate_pct >= 0 else f"{rate_pct}%"
            voice_name = voice or "en-US-AriaNeural"

            communicate = edge_tts.Communicate(
                text,
                voice_name,
                rate=rate_str,
                pitch="+0Hz",
            )
            audio_buffer = io.BytesIO()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_buffer.write(chunk["data"])
            return audio_buffer.getvalue()
        except Exception as exc:
            log.warning("Edge TTS error: %s", exc)
        return b""

    async def _synthesize_piper(self, text: str) -> bytes:
        """Offline fallback. Set PIPER_BIN and PIPER_MODEL (an .onnx voice)."""
        binary = os.getenv("PIPER_BIN", "piper")
        model = os.getenv("PIPER_MODEL", "")
        if not model:
            return b""
        try:
            process = await asyncio.create_subprocess_exec(
                binary, "--model", model, "--output_file", "-",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(process.communicate(text.encode()), timeout=30)
            if process.returncode == 0 and len(stdout) > 100:
                return stdout
            log.warning("Piper failed: %s", stderr.decode(errors="ignore")[:200])
        except Exception as exc:
            log.info("Piper unavailable: %s", exc)
        return b""

    def get_available_voices(self) -> List[Dict[str, str]]:
        return [
            {"id": "en-US-AriaNeural", "name": "Aria (Female)", "language": "en-US"},
            {"id": "en-IN-NeerjaNeural", "name": "Neerja (Indian Female)", "language": "en-IN"},
            {"id": "en-US-AndrewNeural", "name": "Andrew (Natural Male)", "language": "en-US"},
            {"id": "en-US-GuyNeural", "name": "Guy (Male)", "language": "en-US"},
            {"id": "en-US-JennyNeural", "name": "Jenny (Female)", "language": "en-US"},
            {"id": "en-GB-SoniaNeural", "name": "Sonia (British Female)", "language": "en-GB"},
        ]
