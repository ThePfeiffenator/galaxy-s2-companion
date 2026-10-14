set -eu
umask 077
cd /data/data/com.termux/files/home/yt2009/back
exec 9>../galaxy-youtube.lock
flock -n 9 || exit 0
if [ -f ../galaxy-backend.log ] && [ "$(wc -c < ../galaxy-backend.log)" -gt 524288 ]; then
  mv ../galaxy-backend.log ../galaxy-backend.previous.log
fi
exec node backend.js >> ../galaxy-backend.log 2>&1
