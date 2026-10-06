# Android 実機確認

Windows ローカルの Android 17 Gradle Managed Device test は
[Windows GMD 検証](windows-android-gmd.md) の共通 launcher を使います。

## 配布経路

| 目的 | 経路 | applicationId |
| --- | --- | --- |
| same-repository PR ごとの一時確認 | Android checks の `think-canvas-debug-apk` artifact（7 日保存、PR 専用固定署名） | `com.thinkcanvas.internal.debug` |
| fork PR の一時確認 | Android checks の `think-canvas-debug-apk-ephemeral` artifact（7 日保存、run 固有署名） | `com.thinkcanvas.internal.debug` |
| 継続利用・更新確認 | `main` から手動実行する Internal APK release の pre-release | `com.thinkcanvas.internal` |

same-repository PR の artifact は専用の固定鍵で署名し、workflow の `run_number` を
`versionCode` に使います。新しい PR/run の APK は、同じ debug app をアンインストールせず
上書き更新できます。失敗した run の再実行では同じ `versionCode` です。

fork PR には repository secrets が渡らないため、従来どおり run 固有の debug 署名を使います。
この artifact は固定署名 artifact と区別し、継続更新には使いません。

PR debug と internal release は applicationId と署名鍵が異なり、相互に上書き更新されません。
端末では併存できます。

## PR debug 固定署名鍵の初期設定

internal release の鍵は PR build に再利用しません。PR 実機確認専用の PKCS12 keystore を
1 つ作り、以後の same-repository PR で固定して使います。

Windows PowerShell では、たとえば次のように作成します。パスワードはプロンプトで入力し、
コマンドや履歴に直接書きません。

```powershell
keytool -genkeypair -v `
  -keystore .\think-canvas-pr-debug.p12 `
  -storetype PKCS12 `
  -alias think-canvas-pr-debug `
  -keyalg RSA `
  -keysize 3072 `
  -validity 10000 `
  -dname "CN=ThinkCanvas PR Debug,O=ThinkCanvas,C=JP"
```

証明書の SHA-256 fingerprint は次で確認し、`SHA256:` の値を控えます。

```powershell
keytool -list -v `
  -keystore .\think-canvas-pr-debug.p12 `
  -storetype PKCS12 `
  -alias think-canvas-pr-debug
```

Base64 は画面へ表示せずクリップボードへ送れます。

```powershell
$bytes = [IO.File]::ReadAllBytes((Resolve-Path .\think-canvas-pr-debug.p12))
[Convert]::ToBase64String($bytes) | Set-Clipboard
Remove-Variable bytes
```

GitHub repository の **Settings → Secrets and variables → Actions → Repository secrets** に
次を登録します。

| Secret | 内容 |
| --- | --- |
| `THINKCANVAS_PR_DEBUG_KEYSTORE_BASE64` | PR debug 用 PKCS12 keystore の Base64 |
| `THINKCANVAS_PR_DEBUG_STORE_PASSWORD` | keystore のパスワード |
| `THINKCANVAS_PR_DEBUG_KEY_ALIAS` | 署名鍵の alias（上の例では `think-canvas-pr-debug`） |
| `THINKCANVAS_PR_DEBUG_KEY_PASSWORD` | 署名鍵のパスワード。PKCS12 では通常 store password と同じ値 |
| `THINKCANVAS_PR_DEBUG_CERT_SHA256` | 証明書の SHA-256 fingerprint。コロン有無・大文字小文字はどちらでもよい |

keystore とパスワードはリポジトリ外の安全な場所に保管し、別媒体にもバックアップします。
この PR 用鍵を失うか変更すると、既存の `com.thinkcanvas.internal.debug` をそのまま更新できません。
秘密鍵、パスワード、Base64 文字列は PR、Issue、ログ、リポジトリに載せません。

### 既存の PR debug app から固定署名へ切り替える

固定署名導入前の `app-debug.apk` は run ごとの debug key で署名されているため、最初の 1 回だけ
既存 debug app の削除が必要です。過去の CI runner の秘密鍵は保持していないため、この切り替えは
上書き更新できません。

ADB を使う場合は次だけを削除します。

```powershell
adb uninstall com.thinkcanvas.internal.debug
```

`com.thinkcanvas.internal` は別アプリなので削除しません。固定署名版を一度インストールした後は、
以後の same-repository PR artifact をアンインストールせず更新できます。

## internal release 署名鍵の初期設定

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
   許可します。
2. PR の変更を確認する場合は same-repository PR の `think-canvas-debug-apk` を取得し、
   `app-debug.apk` をインストールします。2 つの異なる PR/run artifact で上書き更新が成功することを
   Issue #113 に記録します。
3. internal app は pre-release の `think-canvas.apk` を使います。Obtainium を使う場合は
   `https://github.com/reitojike/think-canvas` を登録し、pre-release を含める設定と APK asset
   `think-canvas.apk` を選びます。
4. 次の pre-release を公開したら、同じ internal app として更新できること、端末内のボードが残ることを
   確認します。更新に失敗した場合は applicationId、署名証明書、versionCode を調べます。
5. キャンバス操作の実機確認は Issue #3 に、端末機種・Android バージョン・確認した操作・結果を
   記録します。

PR debug 固定署名の初回インストールと 2 run 間の上書き更新を確認するまで Issue #113 は完了にしません。
