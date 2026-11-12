#!/bin/bash
# Double-click to start Local Charts on a Mac.
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  echo "First run: setting things up (about a minute)..."
  python3 -m venv .venv || { echo "Python 3 is needed: https://www.python.org/downloads/"; read -p "Press Enter to close"; exit 1; }
  .venv/bin/pip install -q --upgrade pip
  .venv/bin/pip install -q -r requirements.txt
fi
.venv/bin/python app.py
