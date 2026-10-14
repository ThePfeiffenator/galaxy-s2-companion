set -eu
umask 077
python -m pip install --disable-pip-version-check 'ytmusicapi==1.12.3' 'yt-dlp[default]==2026.8.19' 'yt-dlp-ejs==0.8.0'
python - <<'PY'
from importlib.metadata import version
for name in ('ytmusicapi','yt-dlp','yt-dlp-ejs'):
    print(name,version(name))
PY
