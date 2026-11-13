#!/bin/bash
# Publishes site/index.html from develop to the gh-pages branch (the live landing page).
set -e
cd /mnt/sdcard/ZED
git fetch -q origin
export GIT_INDEX_FILE=$(mktemp -u)
git read-tree origin/gh-pages
blob=$(git hash-object -w site/index.html)
git update-index --cacheinfo 100644,$blob,index.html
tree=$(git write-tree)
commit=$(printf 'site: update landing page\n' | git commit-tree $tree -p origin/gh-pages)
rm -f "$GIT_INDEX_FILE"; unset GIT_INDEX_FILE
git push origin $commit:refs/heads/gh-pages
echo "Deployed. https://melhive.github.io/ZED/ updates in about a minute."
