"""Model control (load / unload / switch) is not supported in this version of LocalAI Bridge.

Load your model in your own LLM server (llama.cpp, Ollama, LM Studio, ...). The phone and desktop apps keep their
Models button and show that it is unsupported. The V100-LLM edition (github.com/Abdelhayali/V100-LLM) has the full
model control.
"""
from fastapi import APIRouter, HTTPException

router = APIRouter(prefix="/api/v100")
UNSUPPORTED = "Model control is not supported in this version. Load the model in your LLM server."


@router.get("/status")
async def status():
    return {"launcher": False, "health": "offline", "supported": False}


@router.get("/models")
async def models():
    return []


@router.post("/load")
async def load():
    raise HTTPException(501, UNSUPPORTED)


@router.post("/unload")
async def unload():
    raise HTTPException(501, UNSUPPORTED)
