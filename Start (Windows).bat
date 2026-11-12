@echo off
REM Double-click to start Local Charts on Windows.
cd /d "%~dp0"
if exist .venv\Scripts\python.exe goto run
echo First run: setting things up, about a minute...
py -3 -m venv .venv
if not exist .venv\Scripts\python.exe python -m venv .venv
if not exist .venv\Scripts\python.exe goto nopython
.venv\Scripts\python -m pip install -q --upgrade pip
.venv\Scripts\python -m pip install -q -r requirements.txt
:run
.venv\Scripts\python app.py
pause
exit /b
:nopython
echo Python 3 is needed. Get it from https://www.python.org/downloads/ and tick "Add Python to PATH".
pause
