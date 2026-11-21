#!/data/data/com.termux/files/usr/bin/bash
echo "=================================================="
echo "  MAGIC REPO DIAGNOSTIC - $(date)"
echo "=================================================="

echo ""
echo "### [1] GIT DURUMU"
echo "Aktif dal: $(git branch --show-current)"
echo "Remote URL:"
git remote -v

echo ""
echo "### [2] BUILDS DALI (uzak)"
git fetch origin builds 2>&1 | tail -3

if git rev-parse origin/builds >/dev/null 2>&1; then
  echo "origin/builds içeriği:"
  git ls-tree -r --name-only origin/builds
else
  echo "!! origin/builds dalı YOK!"
fi

echo ""
echo "### [3] plugins.json (origin/builds'ten)"
if git show origin/builds:plugins.json >/tmp/plugins.json 2>/dev/null; then
  echo "--- BOYUT: $(wc -c < /tmp/plugins.json) bayt ---"
  echo "--- İLK 500 KARAKTER ---"
  head -c 500 /tmp/plugins.json
  echo ""
  echo "--- JSON GEÇERLİLİK TESTİ ---"
  python3 -m json.tool /tmp/plugins.json >/dev/null 2>&1 && echo "GEÇERLİ JSON" || echo "!! BOZUK JSON"
else
  echo "!! origin/builds dalında plugins.json YOK!"
fi

echo ""
echo "### [4] RAW URL HTTP KODLARI"
REMOTE_URL=$(git config --get remote.origin.url)
OWNER_REPO=$(echo "$REMOTE_URL" | sed -E 's#.*[:/]([^/]+/[^/]+?)(\.git)?$#\1#')
echo "Tespit edilen owner/repo: $OWNER_REPO"

URL="https://raw.githubusercontent.com/$OWNER_REPO/builds/plugins.json"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -L "$URL")
echo "[$CODE] $URL"

echo ""
echo "### [5] build.yml KOPYALAMA SATIRLARI"
grep -n "plugins.json" build.yml

echo ""
echo "=================================================="
echo "  RAPOR BİTTİ"
echo "=================================================="
