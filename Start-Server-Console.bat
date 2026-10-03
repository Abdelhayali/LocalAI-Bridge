@echo off
rem LocalAI Bridge - headless console mode (prints tunnel URL + QR)
cd /d "%~dp0server"
if not exist .venv\Scripts\python.exe (
  python -m venv .venv || (echo Python 3.10+ is required & pause & exit /b 1)
  .venv\Scripts\python -m pip install -r requirements.txt || (pause & exit /b 1)
)
.venv\Scripts\python.exe main.py
pause
