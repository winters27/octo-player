#!/bin/bash
set -euo pipefail
[[ "$GH_REPO" == VibeProgramm/octo-player ]]
[[ "$BUILD_RUN_ID" =~ ^[0-9]+$ && "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]]
version="1.0.$(git rev-list --count HEAD)-wayland.1"
tag="preview-wayland-$version-run-$BUILD_RUN_ID"
export VERSION="$version" TAG="$tag"
(cd release && sha256sum --check SHA256SUMS)
python3 - <<'PY'
import json, os
from pathlib import Path
repo, version, tag, sha, run = (os.environ[k] for k in ('GH_REPO', 'VERSION', 'TAG', 'SOURCE_SHA', 'BUILD_RUN_ID'))
archive = f'Octo-Wayland-{version}-linux-x64.tar.gz'
assert {p.name for p in Path('release').iterdir()} == {archive, 'SHA256SUMS', 'build.json'}
build = json.loads(Path('release/build.json').read_text())
assert (build['version'], build['commit'], build['run'], build['backend'], build['java']) == (version, sha, run, 'Wayland/EGL', 27)
notes = f'''## Octo — native Wayland / GPU preview

Полноценный музыкальный клиент: вход на Subsonic/Navidrome/Octo, библиотека,
поиск, воспроизведение через Rust-аудиодвижок, полный и мини-плеер.
Нативный Wayland → EGL/OpenGL ES → Skia, без XWayland. Java 27 включена.

### Скачать и запустить

[**{archive}**](https://github.com/{repo}/releases/download/{tag}/{archive}) — Linux x86_64, glibc 2.39+.

```sh
tar -xzf {archive}
./Octo-Wayland/octo-wayland
```

Требуется Wayland-сеанс и аппаратный EGL/OpenGL ES. Программный renderer по
умолчанию отклоняется. F11 — fullscreen, Ctrl+M — мини-плеер.
Закройте другой Octo перед запуском: по умолчанию используется общий профиль.
Отдельный профиль: `OCTO_PROFILE_DIR="$HOME/.local/share/octo-wayland-preview" ./Octo-Wayland/octo-wayland`.
Для выбора файлов нужен `xdg-desktop-portal` с backend вашего рабочего стола.

### Проверки и ограничения

- [Сборка и тесты GitHub Actions](https://github.com/{repo}/actions/runs/{run}). Все файлы опубликованы непосредственно из CI.
- CI проверяет desktop/native-adapter tests, bundled Java 27, реальный аудиотракт,
  HTTPS и Wayland без X11. GPU в CI заменён явным тестовым llvmpipe.
- [Аппаратная проверка и границы preview](https://github.com/{repo}/blob/{sha}/desktop-wayland/README.md).
- Tray и screen-reader integration пока отсутствуют. Положение окна и keep-above задаёт compositor.
- GNOME, Intel/NVIDIA, физический hotplug и suspend/resume пока не подтверждены.
- Автообновление отключено; новую версию устанавливайте заменой распакованного bundle.

Проверка архива: `sha256sum --check --ignore-missing SHA256SUMS`.
Исходный коммит: [{sha[:8]}](https://github.com/{repo}/commit/{sha}).
'''
Path('wayland-notes.md').write_text(notes)
PY
if gh release view "$tag" --json isDraft,targetCommitish > "$RUNNER_TEMP/existing.json"; then
    jq -e --arg sha "$SOURCE_SHA" '.isDraft and .targetCommitish == $sha' "$RUNNER_TEMP/existing.json"
    gh release upload "$tag" release/* --clobber
else
    gh release create "$tag" release/* --target "$SOURCE_SHA" --draft --prerelease --latest=false \
        --title "Octo $version — native Wayland GPU preview" --notes-file wayland-notes.md
fi
release_id=$(gh release view "$tag" --json databaseId --jq .databaseId)
gh api "repos/$GH_REPO/releases/$release_id" > "$RUNNER_TEMP/release.json"
python3 - <<'PY'
import hashlib, json, os
from pathlib import Path
release = json.loads((Path(os.environ['RUNNER_TEMP']) / 'release.json').read_text())
assets = {a['name']: a for a in release['assets']}
files = {p.name: p for p in Path('release').iterdir()}
assert assets.keys() == files.keys()
for name, path in files.items():
    with path.open('rb') as stream:
        digest = 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()
    assert assets[name]['state'] == 'uploaded'
    assert assets[name]['size'] == path.stat().st_size
    assert assets[name]['digest'] == digest
    print('Verified', name)
PY
gh release edit "$tag" --draft=false --prerelease --latest=false
url=$(gh release view "$tag" --json url --jq .url)
echo "### [Download native Wayland preview]($url)" >> "$GITHUB_STEP_SUMMARY"
