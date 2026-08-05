# MapConductor for TomTom

MapConductor の統一マッピング API を [TomTom Orbis Maps Display SDK](https://developer.tomtom.com/android/maps/documentation) 上で実装するモジュールです。

> **スコープ**: `MapView` / コントローラ / ViewState / MapDesign に加え、
> Marker・Polyline・Polygon・Circle・GroundImage・RasterLayer・InfoBubble を実装しています。
> InfoBubble は共通の `MapViewBase` 経由で描画されるため、本モジュール固有のコードはありません。

> **依存**: `com.tomtom.sdk.maps:map-display-standard`（バージョンは `gradle/libs.versions.toml`
> の `tomtomMaps` が管理）。
>
> **検証状況**: コンパイル・単体テスト・ktlint・release AAR 生成が通り、実機（Android）で
> サンプルを起動して地図描画・マーカー表示・マーカータップ→InfoBubble 表示・
> **マーカーのドラッグ（自前実装）**・スクリーン座標変換を確認済みです。

## セットアップ

1. TomTom Developer Portal で API キーを取得します。
2. アプリの `AndroidManifest.xml` にキーを追加します。

```xml
<meta-data
    android:name="TOMTOM_API_KEY"
    android:value="YOUR_TOMTOM_API_KEY" />
```

3. `settings.gradle.kts` に TomTom の Maven リポジトリを追加します。

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
| `polyline/` / `polygon/` / `circle/` | ベクタオーバーレイの描画（測地線はコア共通の補間を使用） |
| `groundimage/` | 画像付き Polygon としてのグラウンドイメージ |
| `raster/TomTomStyleComposer.kt` | ラスターソースを注入した合成 style JSON の組み立て |
| `raster/TomTomRasterLayerSink.kt` | ラスターレイヤー状態の合成スタイルへの反映 |
| `zoom/ZoomAltitudeConverter.kt` | ズーム↔高度の換算 |

## 実装メモ / 既知の制限

- **Compose 埋め込み**: `MapOptions(renderToTexture = true)` を指定している。既定の SurfaceView 描画だと
  Compose の `SubcomposeLayout` の計測と競合し、描画位置がずれるため。
- **サンプルのテーマ**: TomTom の UI オーバーレイ（ロゴ・コンパス等）は AppCompat 系テーマを推奨する
  旨のログ警告が出る（描画自体は動作）。プロダクトでは `Theme.AppCompat`／`Theme.MaterialComponents`
  系テーマの利用を推奨。
- **`fitBounds`**: `CameraOptionsFactory.lookAt(bounds, zoom = null, padding)` に委譲する。
  `zoom = null` とすることでズームは TomTom 側の自動計算に任せ、`padding`(px) はそのまま渡す。
- **ポリゴンの穴 / 測地線**: 頂点列の測地線補間・子午線分割・複数穴の結合はコア共通の
  ユーティリティ（`WGS84Geodesic` / `buildUnwrappedPolygonRings` / `unionHoles`）を再利用し、
  他プロバイダと同じ形状になるようにしている。TomTom は座標間を直線で結ぶため、測地線は
  補間した座標列で近似する。
- **グラウンドイメージ**: `PolygonOptions.isImageOverlay` を有効にした画像付き Polygon で描画する
  （画像がポリゴンの矩形全体へ引き伸ばされる）。境界・画像・色の変更はマーカーと同様、既存の
  ネイティブ Polygon をその場で更新する（削除→再生成しない）。
- **ラスターレイヤー**: TomTom Map Display SDK には実行時に source / layer を追加する公開 API が
  無いため、ベーススタイルへラスターソースを注入した style JSON を組み立て、ローカルの
  `file://` URI として `loadStyle` する（`raster/TomTomStyleComposer.kt`）。
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
