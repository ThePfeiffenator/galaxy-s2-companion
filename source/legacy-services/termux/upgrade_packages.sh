set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get -o Dpkg::Options::=--force-confold upgrade -y -q
node --version
ffmpeg -version | head -n 1
magick --version | head -n 1
