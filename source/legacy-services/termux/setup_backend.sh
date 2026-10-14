set -eu
cd /data/data/com.termux/files/home
test ! -e yt2009/back/config.json
tar -xzf yt2009-pixel.tar.gz
cd yt2009
npm install --ignore-scripts --no-fund --no-audit
node galaxy-init.js
node --check back/backend.js
echo 'YouTube backend prepared'
