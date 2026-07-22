# MapConductor for TomTom

MapConductor の統一マッピング API を [TomTom Orbis Maps Display SDK](https://developer.tomtom.com/android/maps/documentation) 上で実装するモジュールです。

> **スコープ (現状): コア + マーカーのみ**
> `MapView` / コントローラ / ViewState / MapDesign / Marker までを実装しています。
> Polyline / Polygon / Circle / GroundImage / RasterLayer は未実装です（他モジュールを参考に追加可能）。

> **検証状況**: `com.tomtom.sdk.maps:map-display:1.26.7` に対してコンパイル・単体テスト・ktlint・
> release AAR 生成が通り、実機（Android）でサンプルを起動して地図描画・マーカー表示・
> マーカータップ→InfoBubble 表示・**マーカーのドラッグ（自前実装）**・スクリーン座標変換を確認済みです。

## セットアップ

1. TomTom Developer Portal で API キーを取得します。
2. アプリの `AndroidManifest.xml` にキーを追加します。

```xml
<meta-data
    android:name="TOMTOM_API_KEY"
    android:value="YOUR_TOMTOM_API_KEY" />
```

3. `settings.gradle.kts` に TomTom の Maven リポジトリを追加します（`要確認`: 実 URL）。

```kotlin
maven { url = uri("https://repositories.tomtom.com/artifactory/maven") }
```

## 使い方

```kotlin
val mapState = rememberTomTomMapViewState(
    mapDesign = TomTomMapDesign.Standard,
    cameraPosition = MapCameraPosition(position = GeoPoint(52.3676, 4.9041), zoom = 11.0),
)

TomTomMapView(state = mapState, modifier = Modifier.fillMaxSize()) {
    Marker(
        MarkerState(
            position = GeoPoint(52.3676, 4.9041),
            icon = DefaultMarkerIcon().copy(label = "Amsterdam"),
        ),
    )
}
```

サンプルは `sample-app/` を参照してください。

## 構成

| ファイル | 役割 |
| --- | --- |
| `TomTomMapView.kt` | Compose エントリーポイント / コントローラ生成 |
| `TomTomMapViewController.kt` | カメラ・マーカー・デザインの中枢コントローラ |
| `TomTomMapViewStateImpl.kt` | `rememberTomTomMapViewState` と状態保存 |
| `TomTomMapViewHolder.kt` | `MapView` / `TomTomMap` のラッパ、座標変換 |
| `TomTomMapDesign.kt` | スタイル（マップデザイン）定義 |
| `MapCameraPosition.kt` | カメラ座標の相互変換 |
| `GeoPoint.kt` / `GeoRectBounds.kt` | 座標型の相互変換 |
| `marker/` | ネイティブマーカーの描画・イベント |
| `zoom/ZoomAltitudeConverter.kt` | ズーム↔高度の換算 |

## 実装メモ / 既知の制限

- **Compose 埋め込み**: `MapOptions(renderToTexture = true)` を指定している。既定の SurfaceView 描画だと
  Compose の `SubcomposeLayout` の計測と競合し、描画位置がずれるため。
- **サンプルのテーマ**: TomTom の UI オーバーレイ（ロゴ・コンパス等）は AppCompat 系テーマを推奨する
  旨のログ警告が出る（描画自体は動作）。プロダクトでは `Theme.AppCompat`／`Theme.MaterialComponents`
  系テーマの利用を推奨。
- **`fitBounds`**: TomTom に矩形フィットの直接 API が無いため、境界中心へ移動するのみの簡易実装
  （ズーム計算は未実装）。
- **マーカー変更/移動**: TomTom の `Marker` は `coordinate` / `isVisible` が可変で `setPinImage()` も
  あるため、位置・表示・アイコンの更新は既存インスタンスをその場で更新する（削除→再生成しない）。
  ドラッグ中に毎フレーム削除→再生成するとメインスレッドが詰まって ANR になるため重要。
- **ドラッグ（自前実装）**: TomTom はマーカーのネイティブドラッグを持たないため、`MapView` の
  `MotionEvent` を直接処理して実装している（ArcGIS モジュールと同じ方針）。draggable マーカー上の
  タッチでジェスチャを占有して地図パンを抑止し、スロップ超えの移動でドラッグ開始→指に追従、
  離して確定。動かず離した場合はクリック（`onClick`）として扱う。指の画面座標→地理座標は
  `TomTomMap.coordinateForPoint`、マーカーの再配置は `Marker.coordinate` 更新で行う。

## ライセンス

Apache License 2.0
