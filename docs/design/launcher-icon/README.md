# ランチャーアイコン

選択されたB案「思考の配置」を使う。離れた3枚のカードとつながりで、
キャンバス上の位置関係と思考の組み替えを表す。朱色のカードを視覚的な焦点にする。
製品UIの墨色 `#23211E`、紙色 `#FCFCFB`、朱色 `#C54B32` を使う。
検証版は青緑 `#24666B` とアイコン内の `TEST`、先頭に `TEST` を置くラベルで区別する。

## 配布と選択

| build type | 実際の配布経路 | ランチャーラベル | アイコン |
| --- | --- | --- | --- |
| `debug` | Android checks の PR APK artifact | `TEST ThinkCanvas` | 青緑、TEST入り |
| `internal` | Internal APK release の `think-canvas.apk` | `ThinkCanvas` | 墨色、朱色のカード |
| `release` | 現在は直接配布するworkflowなし | `ThinkCanvas` | 墨色、朱色のカード |

配布経路の根拠は [実機確認runbook](../../runbooks/device-verification.md)、
`android.yml` の `assembleDebug`、`internal-release.yml` の `assembleInternal`。
継続利用する `internal` APKはpre-releaseとして配布されるが、PR検証APKとは別である。
build type名だけで「検証版」を決めず、配布目的に合わせて `debug` のみに上書きを置く。
applicationId、署名、version設定、保存データ、配布workflowは変更しない。

## 編集するファイル

- `release.svg` / `test.svg`: フォントや外部画像に依存しない編集用SVG。背景を含む108×108のレイヤー合成。
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: 製品アイコンの前景。
- `app/src/debug/res/drawable/ic_launcher_foreground.xml`: 検証アイコンの前景。
- 各source setの `ic_launcher_monochrome.xml`: テーマアイコン用。TESTはbadgeから文字形状を抜いた `evenOdd` パスで保持する。
- 各source setの `values/launcher_icon.xml`: 背景色。
- `app/src/debug/res/values/strings.xml`: 検証版のアプリ名。

実行時の正本はAndroidリソース。SVGを変更した場合は同じパス・色・縮尺をVectorDrawableにも反映する。
`TEST` は文字をパスにしており、端末のフォントに依存しない。

## Androidの形状と密度

108dpの背景・前景を分離し、主要図形とTEST badgeを中央66dpの範囲に収める。
背景にはランチャーが使うmaskを焼き込まない。通常・roundの両リソースに同じadaptive iconを使い、
端末のmaskに従う。`mipmap-anydpi` に共通のadaptive iconを置き、API 33以降は
明示したmonochromeレイヤーを使う。それ以前のAndroidはこの子要素を無視する。

`minSdk 26` のため、対応Android端末はAPI 26のadaptive iconを使える。
追加の密度別PNGやlegacy fallbackは不要。前景のVectorDrawableを全密度で拡大縮小する。
`debug` ではadaptive iconが参照する前景、背景色、monochromeを上書きし、TEST表示を保つ。

仕様の根拠: [Android Developers: Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
実機ではランチャーごとにmask、余白、ラベルの省略位置が異なるため、最終確認時に
両アプリの並びとテーマ表示も確認する。
