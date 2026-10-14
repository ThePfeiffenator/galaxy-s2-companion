set -eu
umask 077
cd "$HOME/galaxy-music"
# The process lock is released by the kernel if Python exits or the phone restarts.
exec flock -n "$HOME/galaxy-music/runtime.lock" python "$HOME/galaxy-music/music_server.py"
