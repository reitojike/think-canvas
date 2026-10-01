# Android 実機確認

Windows の Android / Gradle Managed Device 検証は
[Windows Android / GMD 検証](windows-android-gmd.md) の共通 launcher を使います。

## 配布経路

| 目的 | 経路 | applicationId |
| --- | --- | --- |
| PR ごとの一時確認 | Android checks の `think-canvas-debug-apk` artifact（7 日保存） | `com.thinkcanvas.internal.debug` |
| 継続利用・更新確認 | `main` から手動実行する Internal APK release の pre-release | `com.thinkcanvas.internal` |

PR の artifact は zip で取得します。実機に継続インストールする APK は、
pre-release の `think-canvas.apk` を使います。両者は applicationId と署名が異なり、
相互に上書き更新されません。端末では併存できます。

## 署名鍵の初期設定

`internal-release` 環境の GitHub Actions Secrets に以下を登録します。

| Secret | 内容 |
| --- | --- |
| `THINKCANVAS_INTERNAL_KEYSTORE_BASE64` | PKCS12 keystore の Base64 |
| `THINKCANVAS_INTERNAL_STORE_PASSWORD` | keystore のパスワード |
| `THINKCANVAS_INTERNAL_KEY_ALIAS` | 署名鍵の alias |
| `THINKCANVAS_INTERNAL_KEY_PASSWORD` | 署名鍵のパスワード |

keystore はリポジトリ外の安全な場所に保管し、別媒体にもバックアップします。鍵または
パスワードを失うと、同じ applicationId の既存インストールを更新できません。鍵、パスワード、
Base64 文字列は PR、Issue、ログ、リポジトリに載せません。

## pre-release を作る

1. `main` の CI と対象機能のレビューを確認します。
2. GitHub Actions の **Internal APK release** を `main` で手動実行し、未使用の
   `vX.Y.Z-alpha.N` または `vX.Y.Z-dev.N` と、配布する main commit の 40 桁 SHA を入力します。
   workflow はその commit が main の履歴に含まれることを確認します。
3. workflow が lint・単体テストを通し、APK の署名、applicationId、versionCode を検証してから
   pre-release に `think-canvas.apk` を添付します。`versionCode` にはこの workflow の
   `run_number` を使うため、新規実行ごとに増えます。失敗した実行の再実行では同じ値です。
4. Release の説明と APK を確認します。直接リンクは
   `https://github.com/reitojike/think-canvas/releases/download/<tag>/think-canvas.apk`
   です。`releases/latest/download/...` は pre-release を指さないため使いません。

## 端末で確認する

1. 初回は Android の設定で、ダウンロードに使うアプリに「不明なアプリのインストール」を
   許可します。APK を開き、`com.thinkcanvas.internal` をインストールします。
2. Obtainium を使う場合は `https://github.com/reitojike/think-canvas` を登録し、
   pre-release を含める設定と APK asset `think-canvas.apk` を選びます。
3. 次の pre-release を公開したら、同じアプリとして更新できること、端末内のボードが残ることを
   確認します。更新に失敗した場合は applicationId、署名証明書、versionCode を調べます。
4. 配布経路の初回インストール・更新結果は Issue #20 に記録します。キャンバス操作の実機確認は
   Issue #3 に、端末機種・Android バージョン・確認した操作・結果を記録します。

初回の実機更新確認が終わるまで Issue #20 は完了にしません。
