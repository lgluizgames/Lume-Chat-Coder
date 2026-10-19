#!/usr/bin/env bash
set -euo pipefail
# Run from root of project. Requires upstream at ./llama-src (GitHub Actions downloads it).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="$ROOT/llama-src/examples/llama.android"
APP="$BASE/app"
LIB="$BASE/lib"
[ -f "$APP/build.gradle.kts" ] || { echo 'Erro: codigo-fonte do llama.cpp nao encontrado.'; exit 1; }
mkdir -p "$APP/src/main/assets"
cp "$ROOT/overlay/MainActivity.kt" "$APP/src/main/java/com/example/llama/MainActivity.kt"
cp "$ROOT/overlay/index.html" "$APP/src/main/assets/index.html"
export LUME_BASE="$BASE"
python3 - <<'PY'
import os
from pathlib import Path
base=Path(os.environ['LUME_BASE'])
for module in ('app','lib'):
 p=base/module/'build.gradle.kts'
 s=p.read_text()
 if 'minSdk = 33' not in s: raise RuntimeError(f'{module}: versao de minSdk upstream mudou; revisar patch!')
 s=s.replace('minSdk = 33','minSdk = 29')
 if module=='app':
  s=s.replace('applicationId = "com.example.llama.aichat"','applicationId = "com.lumechat.local"')
  s=s.replace('versionName = "1.0"','versionName = "0.4-coder"')
  s=s.replace('''debug {
            isMinifyEnabled = true
            isShrinkResources = true''','''debug {
            isMinifyEnabled = false
            isShrinkResources = false''')
  s=s.replace('implementation(project(":lib"))','implementation(project(":lib"))\n    implementation("androidx.webkit:webkit:1.14.0")')
 else:
  s=s.replace('abiFilters += listOf("arm64-v8a", "x86_64")','abiFilters += listOf("arm64-v8a")')
  s=s.replace('''arguments += "-DGGML_LLAMAFILE=OFF"''','''arguments += "-DGGML_LLAMAFILE=OFF"\n                arguments += "-DGGML_OPENMP=OFF"''')
 p.write_text(s)
cpp=base/'lib/src/main/cpp/CMakeLists.txt'
cmake_src=cpp.read_text()
cmake_src=cmake_src.replace('set(GGML_OPENMP ON)', 'set(GGML_OPENMP OFF)')
cpp.write_text(cmake_src)
engine_cpp=base/'lib/src/main/cpp/ai_chat.cpp'
engine_src=engine_cpp.read_text()
engine_src=engine_src.replace('DEFAULT_CONTEXT_SIZE    = 8192', 'DEFAULT_CONTEXT_SIZE    = 3072')
engine_src=engine_src.replace('BATCH_SIZE              = 512', 'BATCH_SIZE              = 128')
engine_cpp.write_text(engine_src)
strings=base/'app/src/main/res/values/strings.xml'
st=strings.read_text()
import re
st=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">Lume Chat Coder</string>',st)
strings.write_text(st)
manifest=base/'app/src/main/AndroidManifest.xml'
text=manifest.read_text()
text=text.replace('android:allowBackup="true"','android:allowBackup="true"\n        android:usesCleartextTraffic="false"')
manifest.write_text(text)
PY
printf '%s\n' 'Arquivos aplicados. Gerando APK...'
