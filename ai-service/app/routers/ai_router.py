import logging

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from fastapi.responses import Response
from pydantic import BaseModel, Field
from typing import List, Optional
from app.security import require_service_token
from app.services.llm_service import LLMService
from app.services.resume_parser import ResumeParser
from app.services.resume_writer import ResumeWriter
from app.services.evaluation_service import EvaluationService
from app.services.voice_service import VoiceService
from app.services.emotion_service import EmotionService

log = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(require_service_token)])


def _internal_error(exc: Exception) -> HTTPException:
    """Log the real cause; never return stack traces or provider text to callers."""
    log.exception("AI service request failed", exc_info=exc)
    return HTTPException(status_code=500, detail="AI service error")


llm_service = LLMService()
resume_parser = ResumeParser()
resume_writer = ResumeWriter()
evaluation_service = EvaluationService()
voice_service = VoiceService()
emotion_service = EmotionService()


class QuestionGenerationRequest(BaseModel):
    job_description: str = Field(max_length=20000)
    resume_text: Optional[str] = Field(default="", max_length=50000)
    count: int = Field(default=10, ge=1, le=30)
    experience_level: str = Field(default="MID", max_length=20)


class FollowUpRequest(BaseModel):
    question: str = Field(max_length=2000)
    answer: str = Field(max_length=20000)


class EvaluationRequest(BaseModel):
    question: str = Field(max_length=2000)
    answer: str = Field(max_length=20000)
    expected_answer: Optional[str] = Field(default="", max_length=5000)


class ResumeParseRequest(BaseModel):
    resume_text: str = Field(max_length=100000)


class ResumeExperienceItem(BaseModel):
    title: str = Field(default="", max_length=120)
    company: str = Field(default="", max_length=120)
    period: str = Field(default="", max_length=60)
    description: str = Field(default="", max_length=3000)


class ResumeEducationItem(BaseModel):
    degree: str = Field(default="", max_length=120)
    school: str = Field(default="", max_length=120)
    period: str = Field(default="", max_length=60)


class ResumeProjectItem(BaseModel):
    name: str = Field(default="", max_length=120)
    detail: str = Field(default="", max_length=400)


class ResumeGenerateRequest(BaseModel):
    full_name: str = Field(default="", max_length=100)
    role: str = Field(default="", max_length=120)
    email: str = Field(default="", max_length=200)
    phone: str = Field(default="", max_length=40)
    location: str = Field(default="", max_length=100)
    linkedin: str = Field(default="", max_length=200)
    github: str = Field(default="", max_length=200)
    summary: str = Field(default="", max_length=2000)
    skills: str = Field(default="", max_length=2000)
    experience: List[ResumeExperienceItem] = Field(default_factory=list, max_length=10)
    education: List[ResumeEducationItem] = Field(default_factory=list, max_length=5)
    projects: List[ResumeProjectItem] = Field(default_factory=list, max_length=8)


class JDAnalysisRequest(BaseModel):
    job_description: str = Field(max_length=20000)


class ReportGenerationRequest(BaseModel):
    interview_id: int
    evaluations: List[dict] = Field(default_factory=list, max_length=50)
    average_score: int = 0
    total_questions: int = 0
    answered_questions: int = 0
    job_description: str = Field(default="", max_length=500)


class SynthesizeRequest(BaseModel):
    text: str = Field(max_length=5000)
    voice: str = Field(default="en-US-AriaNeural", max_length=60)
    rate: float = Field(default=1.0, ge=0.5, le=2.0)


class EmotionAnalysisRequest(BaseModel):
    audio_features: dict
    transcript: Optional[str] = Field(default="", max_length=20000)


@router.post("/generate-questions")
async def generate_questions(request: QuestionGenerationRequest):
    try:
        questions = await llm_service.generate_questions(
            job_description=request.job_description,
            resume_text=request.resume_text,
            count=request.count,
            experience_level=request.experience_level
        )
        return questions
    except Exception as e:
        raise _internal_error(e)


@router.post("/generate-followup")
async def generate_followup(request: FollowUpRequest):
    try:
        follow_up = await llm_service.generate_followup(
            question=request.question,
            answer=request.answer
        )
        return {"follow_up": follow_up}
    except Exception as e:
        raise _internal_error(e)


@router.post("/evaluate-answer")
async def evaluate_answer(request: EvaluationRequest):
    try:
        evaluation = await evaluation_service.evaluate(
            question=request.question,
            answer=request.answer,
            expected_answer=request.expected_answer
        )
        return evaluation
    except Exception as e:
        raise _internal_error(e)


@router.post("/parse-resume")
async def parse_resume(request: ResumeParseRequest):
    try:
        parsed_data = resume_parser.parse(request.resume_text)
        return parsed_data
    except Exception as e:
        raise _internal_error(e)


@router.post("/generate-resume")
async def generate_resume(request: ResumeGenerateRequest):
    try:
        return await resume_writer.generate(request.model_dump())
    except Exception as e:
        raise _internal_error(e)


@router.post("/analyze-jd")
async def analyze_jd(request: JDAnalysisRequest):
    try:
        analysis = await llm_service.analyze_job_description(request.job_description)
        return analysis
    except Exception as e:
        raise _internal_error(e)


@router.post("/generate-report")
async def generate_report(request: ReportGenerationRequest):
    try:
        report = await llm_service.generate_report(
            interview_id=request.interview_id,
            evaluations=request.evaluations,
            average_score=request.average_score,
            total_questions=request.total_questions,
            answered_questions=request.answered_questions,
            job_description=request.job_description
        )
        return report
    except Exception as e:
        raise _internal_error(e)


@router.post("/transcribe")
async def transcribe_audio(audio: UploadFile = File(...)):
    try:
        audio_data = await audio.read()
        if not audio_data:
            raise HTTPException(status_code=400, detail="Empty audio upload")
        if len(audio_data) > 25 * 1024 * 1024:
            raise HTTPException(status_code=413, detail="Audio must be smaller than 25 MB")
        result = await voice_service.transcribe(audio_data)
        return {
            "transcript": result.get("transcript", ""),
            "language": result.get("language", "en"),
            "duration": result.get("duration", 0.0),
            "provider": result.get("provider", "mock"),
            "confidence": result.get("confidence", 0.0),
            **({"error": result["error"]} if result.get("error") else {}),
        }
    except HTTPException:
        raise
    except Exception as e:
        raise _internal_error(e)


@router.post("/synthesize")
async def synthesize_speech(request: SynthesizeRequest):
    try:
        audio_data = await voice_service.synthesize(request.text, request.voice, request.rate)
        # Detect format from header
        if audio_data[:4] == b'RIFF':
            media_type = "audio/wav"
        elif audio_data[:3] == b'ID3' or audio_data[:2] == b'\xff\xfb':
            media_type = "audio/mpeg"
        else:
            media_type = "audio/mpeg"
        return Response(content=audio_data, media_type=media_type)
    except Exception as e:
        raise _internal_error(e)


@router.post("/analyze-emotion")
async def analyze_emotion(request: EmotionAnalysisRequest):
    try:
        emotion_data = emotion_service.analyze(request.audio_features, request.transcript)
        return emotion_data
    except Exception as e:
        raise _internal_error(e)


@router.get("/voices")
async def get_voices():
    return voice_service.get_available_voices()
