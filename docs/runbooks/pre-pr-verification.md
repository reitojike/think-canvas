# PR 前の検証

この手順は PR 作成前の検証を扱います。PR 後の CI とレビューの収束は別の手順です。

1. Issue、依頼内容、Constitution、変更対象の現行 spec と plan を確認し、Task Contract の
   停止条件と変更範囲を確定します。受け入れ条件ごとに、実装と観測可能な検証結果を対応付けます。
2. リポジトリの root で、現行 Android CI に対応する基本検証を実行します。

   ```powershell
   .\gradlew.bat :app:lintDebug
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleDebug
   pwsh -File scripts/check-public-boundary.ps1
   git diff --check
   git diff --check origin/main...HEAD
   ```

   macOS / Linux では Gradle コマンドを `./gradlew` に置き換えます。Room schema を変更した
   場合は生成 schema と追跡済み schema の一致も確認します。保存、gesture、アクセシビリティ
   などの変更では、該当する instrumentation test と Android emulator の確認を追加します。
   実装の形だけをなぞるテストではなく、利用者に見える振る舞いを検証します。
3. 差分と変更ファイル一覧を読み、秘密情報、個人情報、ローカルパス、非公開サービス情報、
   会話ログ、PRD の本文、HTML モックの内容が公開されないことを確認します。script は典型的な
   パターンを補助的に検出するもので、目視確認を置き換えません。
4. PR の説明に Issue、関連 spec、変更内容、標準から外れた判断、検証結果、未確認事項を記載します。

JDK、SDK、network、権限などの環境要因で検証を完走できないときも、実行可能な項目はすべて
実行します。未実行項目、理由、代替確認を PR に記録し、degraded verification を全項目の
成功と同一視しません。再現するコードの不具合は修正し、解消できなければ `HOLD` にします。
