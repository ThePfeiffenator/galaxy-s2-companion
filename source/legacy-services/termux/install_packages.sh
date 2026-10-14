set -eu
if [ "${1:-}" != "--fresh-install" ] || [ -e "$HOME/yt2009" ] || [ -e "$HOME/galaxy-music/client.json" ]; then
  echo 'Use --fresh-install only in a new Termux installation, before creating runtimes or authorizing accounts.' >&2
  exit 2
fi
# The APK bootstrap can predate current repository libraries. Updating only
# ffmpeg leaves an older libc++ that cannot load its current dependencies.
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get -o Dpkg::Options::=--force-confold upgrade -y -q
apt-get -o Dpkg::Options::=--force-confold install -y -q nodejs-lts ffmpeg imagemagick git python util-linux
node --version
ffmpeg -version | head -n 1
magick --version | head -n 1
python --version
command -v flock
