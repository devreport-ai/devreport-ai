#!/usr/bin/env bash
set -euo pipefail

git switch dev
git pull origin dev
git switch -c chore/issue-templates

mkdir -p .github/ISSUE_TEMPLATE
cp -R "$(dirname "$0")/.github/ISSUE_TEMPLATE/." .github/ISSUE_TEMPLATE/

git add .github/ISSUE_TEMPLATE
git commit -m "chore: add issue templates"
git push -u origin chore/issue-templates

echo
echo "템플릿 브랜치 Push 완료."
echo "GitHub에서 base=dev, compare=chore/issue-templates PR을 생성하세요."
echo "주의: 이슈 템플릿 선택 화면은 기본 브랜치(main)에 반영된 뒤 활성화됩니다."
