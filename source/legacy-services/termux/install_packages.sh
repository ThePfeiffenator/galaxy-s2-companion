set -eu
apt-get update -q
apt-get install -y -q nodejs-lts ffmpeg imagemagick git python util-linux
node --version
ffmpeg -version | head -n 1
magick --version | head -n 1
python --version
command -v flock
