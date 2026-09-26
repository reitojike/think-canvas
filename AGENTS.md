# リポジトリの案内

このファイルは、判断に使う資料の所在を示します。各資料の詳しい規則はここに重複させません。

- 変更の目的と範囲は、該当する GitHub Issue と pull request で確認します。Git の履歴は
  過去の判断を調べるために使い、古い branch を現在の製品要件の根拠にはしません。
- 製品要件の authority は PRD です。HTML モックは reference implementation です。
  どちらもこの公開リポジトリには置きません。機能の振る舞いは、作成後の
  `specs/**/spec.md` でレビューできる形にします。
- [Constitution](.specify/memory/constitution.md) は製品と開発の原則を定めます。
  Spec Kit の成果物は、対象の機能ごとに管理します。
- [変更・レビュー手順](docs/runbooks/change-review.md) は PR 前の検証、レビュー、merge 後の
  Issue 完了までの確認範囲を定めます。機械的な合否は実際の
  [Android CI](.github/workflows/android.yml) を確認します。
- PR のレビューは原則 Codex または Claude に依頼します。実装担当と異なる provider が
  利用できる場合は優先します。GitHub Copilot と CodeRabbit の指摘は参考にできますが、
  このレビューの代わりにはしません。現行 PR head の結果と未解決 thread を確認します。
- Android の標準と既存の開発手段を優先します。標準から外れる理由は該当する spec または
  plan に残します。Issue に具体的な必要性が示されるまでは、独自の手順や仕組みを増やしません。
- プロジェクトが作成する Markdown は原則日本語で書きます。固有名詞、コマンド、識別子、
  訳すと意味がぶれる専門用語は原語のまま使います。
