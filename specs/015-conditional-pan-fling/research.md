# 調査と判断

参照main e441cab。read-only checkpointは [Issue #102](https://github.com/reitojike/think-canvas/issues/102#issuecomment-5999753569) に記録済み。

## 決定

即停止panを維持する候補、弱い条件付き慣性、通常scroll相当の慣性を比較した。大きなボードの探索と精密位置合わせを両立するため、利用者は「払いに慣性、ゆっくりは即停止」を選択した。実装後のPixel9a/Android17比較は未実施。

現行BOM/cacheで標準VelocityTracker/addPointerInputChange/calculateVelocity、AnimationState、animateDecay、splineBasedDecayを利用できる。UPをtrackerへ渡すと停止後40ms超の古い速度がresetされる。screen px/sとViewport.panのscreen pxを直接対応させる。独自積分やdependency更新は不要。

400dp/s以上のflick、上限1800dp/sのvector magnitude policyを採用し、platform min/maxでも制限する。標準Android friction0.015での理論追加移動/時間は400:39.6dp/283ms、800:131.9dp/471ms、1200:266.7dp/635ms、1800:539.2dp/856ms。これはcache標準式からの参考値であり端末評価を代替しない。

既存animation boundaryを使う理由は、camera writer・同期取消・表示履歴の責任を一つに保つため。現行DOWNはchrome/editorチェック後に停止するため、Initial-passでの早期停止が必要。標準trackerとdecayを使い、標準scrollableへの全面移行はfree canvasの既存gesture arbitrationを変えるため採用しない。

## Primary references

- [VelocityTracker API](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/util/VelocityTracker)
- [VelocityTracker source](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/util/VelocityTracker.kt)
- [AnimationState API](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/AnimationState)
- [標準gesture/decay例](https://developer.android.com/develop/ui/compose/animation/advanced#gestures)
- [Android spline decay source](https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/src/androidMain/kotlin/androidx/compose/animation/SplineBasedFloatDecayAnimationSpec.android.kt)

## touch physicsとanimation設定

現行Foundation DefaultFlingBehaviorはmotionDurationScale=1fでdecayを実行する。素のanimateDecayは継承scale0で全targetへ一frameで跳ぶため、本featureもfling coroutineだけ1xを指定する。標準physics/parent Job/frame clock/取消を維持し、通常の表示animationのsystem scaleは変更しない。native回帰でsystem scale0の複数frame移動を観測する。

[Viewsと合わせるAndroidX変更](https://android.googlesource.com/platform/frameworks/support/+/3534bf4029862f6ab15c70f44b1025f49e338412%5E%21/)、[Foundation source](https://android.googlesource.com/platform/frameworks/support/+/f68402285edc35592203bcd92aaf1af3636464a4/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Scrollable.kt)。
