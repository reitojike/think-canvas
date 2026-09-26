# 変更とレビューの手順

この runbook は、ThinkCanvas の変更を PR の作成から Issue の完了まで確認する手順です。
実行する CI は [Android checks](../../.github/workflows/android.yml) を基準にします。

## PR を作成する前

1. 該当する Issue、[Constitution](../../.specify/memory/constitution.md)、機能の現行の
   spec と plan を読みます。製品の振る舞い、技術設計、運用手順、変更履歴は、それぞれの
   成果物で管理します。
2. 変更が Issue の範囲に収まっていることと、受け入れ条件に観測可能な検証結果があることを
   確認します。標準パターンから外れる判断とアクセシビリティへの影響は spec または plan に
   記録します。
3. リポジトリの root で、現在の必須チェックを実行します。

   ```powershell
   .\gradlew.bat :app:lintDebug
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleDebug
   pwsh -File scripts/check-public-boundary.ps1
   git diff --check
   git diff --check origin/main...HEAD
   ```

   macOS / Linux では各 Gradle コマンドの先頭を `./gradlew` に置き換えます。
   `git diff --check` は作業中の変更と PR の base からの差分をそれぞれ確認します。保存や空間配置の
   不変条件は、実装の形をなぞるだけでなく、振る舞いを確かめるテストで検証します。
4. 変更ファイルの一覧と差分を読み、秘密情報、ローカルパス、個人情報、非公開サービス、
   会話ログ、PRD の本文、HTML モックの内容が含まれていないか確認します。チェック
   script は典型的なパターンの検出補助です。PR の説明には Issue、関連する spec、
   標準からの例外、検証結果、残る制約を記載します。

JDK、SDK、network、権限などの環境要因でローカルビルドを実行できない場合も、実行可能な
確認はすべて行います。どのコマンドが、なぜ実行できなかったかを明記し、代替確認を
ビルド成功と同等には扱いません。再現できるソースの不具合は、変更を完成とする前に直します。

## PR を作成した後

PR 作成は途中の確認点です。依頼された作業が merge 可能な状態まで含む場合は、CI とレビューの
結果を確認し、必要な修正と再確認まで続けます。

1. PR の説明から Issue と spec にたどれることを確認し、原則 Codex または Claude に
   レビューを依頼します。実装担当と異なる provider が利用できる場合は優先します。
   GitHub Copilot と CodeRabbit の指摘は参考にできますが、このレビューの代わりには
   しません。依頼先、依頼時の head、結果を PR から確認できる形で残します。
   Codex と Claude のどちらにも依頼できない場合は理由を記録し、レビュー要件の例外を
   判断するまで merge を保留します。CI の結果、PR の base の状態、レビューの指摘を
   **現在の PR head** に対して確認します。必須の lint・単体テスト・ビルド・公開情報境界
   チェックを実際の workflow と照合します。
2. 失敗した CI とレビューの指摘を調べ、必要な修正を行います。該当しない指摘は理由を
   記録し、修正済み・古くなったものを含む review thread は GitHub 上で解決します。
   データ消失、privacy、公開情報の境界に関わる変更では、可能なら別の provider による
   独立した観点のレビューも受けます。
3. 修正を push したら、新しい head で CI とレビューを再確認します。base の更新や再レビュー
   依頼によって確認対象が変わった場合も、古い結果を再利用しません。結果が未着・失敗・
   不明、または未解決 thread がある間は merge しません。新しい head ではレビュー結果も
   再確認し、以前の head の結果だけを根拠にしません。
4. Issue の受け入れ条件を実装、テスト、必要な実機確認と照合します。未確認の条件は PR に
   明記します。現行 head の必須 CI が成功し、レビュー指摘が解消され、リポジトリ側で
   merge 可能な状態になってから merge します。

## merge 後に Issue を完了する

1. PR が実際に merge されたことと merge commit を確認します。main の CI がある場合は
   その結果も確認します。PR の merge だけで Issue の完了を推定しません。
2. Issue の最新本文と受け入れ条件を読み直し、各項目を merge 済みの成果物と検証結果に
   照らして判定します。達成済みの checkbox だけを更新し、未達・未確認の項目は残します。
   他の人が本文を更新していたら、最新の内容から判断し直します。
3. 全項目を満たし、既知の残作業がない単一機能の Issue には、実装 PR、merge commit、
   検証結果、レビュー結果を短く記録して `completed` で close します。親 Issue や進行管理の
   Issue は、子 Issue の完了状況と残作業を確認して別に判断します。未達や証拠不足があれば
   close せず、残る作業を記録します。
