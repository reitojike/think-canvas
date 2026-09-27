# リポジトリの案内

このファイルは、判断に使う現在の資料への案内です。製品仕様や運用規則はここに複製しません。

- 変更の目的と範囲は、該当する GitHub Issue と pull request で確認します。Git の履歴は
  過去の判断を調べるために使い、古い branch を現在の製品要件の根拠にはしません。
- 製品要件の authority は非公開の PRD です。HTML モックは非公開の reference implementation
  です。公開リポジトリでレビューする機能の振る舞いは、merge 済みの現行
  `specs/**/spec.md` を参照します。作業中の Spec Kit 成果物は、merge されるまで現行の
  PRD や spec を暗黙に置き換えません。
- [Constitution](.specify/memory/constitution.md) は製品と開発の原則を定めます。
  Spec Kit の成果物は対象機能ごとに管理し、技術設計はその plan で確認します。
- 作業の停止条件と手順の入口は [変更とレビューの案内](docs/runbooks/change-review.md) を確認します。
  PR 前は [検証手順](docs/runbooks/pre-pr-verification.md)、PR 後は
  [収束手順](docs/runbooks/post-pr-convergence.md)、merge 後の Issue 完了は
  [完了手順](docs/runbooks/post-merge-issue-closure.md) に従います。
- 機械的な合否は実際の [Android CI](.github/workflows/android.yml) とテストで確認します。
  実機への配布と確認は [Android 実機確認](docs/runbooks/device-verification.md) を参照します。
- Android の標準と既存の開発手段を優先します。標準から外れる理由は該当する spec または
  plan に残します。Issue に具体的な必要性が示されるまでは、独自の手順や仕組みを増やしません。
- プロジェクトが作成する Markdown は原則日本語で書きます。固有名詞、コマンド、識別子、
  訳すと意味がぶれる専門用語は原語のまま使います。
