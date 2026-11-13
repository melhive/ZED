#!/bin/bash
# Publishes the landing page and legal pages (site/*.html) to the gh-pages branch.
# Regenerates terms.html and privacy.html from the app's LegalScreens.kt first, so they always match the app.
set -e
cd "$(dirname "$0")/.."
python3 -I scripts/legal_pages.py "$PWD"
git fetch -q origin
export GIT_INDEX_FILE=$(mktemp -u)
git read-tree origin/gh-pages
for f in index.html terms.html privacy.html; do
  blob=$(git hash-object -w "site/$f")
  git update-index --add --cacheinfo 100644,$blob,$f
done
tree=$(git write-tree)
rm -f "$GIT_INDEX_FILE"; unset GIT_INDEX_FILE
if [ "$tree" = "$(git rev-parse origin/gh-pages^{tree})" ]; then echo "Site already up to date."; exit 0; fi
commit=$(printf 'site: update landing and legal pages\n' | git commit-tree $tree -p origin/gh-pages)
git push origin $commit:refs/heads/gh-pages
echo "Deployed. https://melhive.github.io/ZED/ updates in about a minute."
