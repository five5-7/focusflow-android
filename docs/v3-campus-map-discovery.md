# V3 校园地图发现方案

## 目标

普通用户不需要制作地点包、填写坐标或理解分区。默认流程为自动发现，地图点选仅用于补充缺失地点，JSON 只保留为批量迁移和备份入口。

## 用户流程

1. 用户选择学校与校区，FocusFlow 围绕校区边界搜索教学楼、图书馆、实验、运动、餐饮、宿舍和交通 POI。
2. 根据地图提供的 POI 类型推断地点用途，合并重复名称，并展示少量低置信度项目供确认。
3. 搜不到的地点允许在地图上点选。逆地理编码先建议地址和附近 POI，用户只需确认或修正名称与用途。
4. 当前定位是单独的可选权限；搜索校园和在地图上点选不以持续定位为前提。
5. 地点与用户确认结果缓存在本机，日程推荐只读取已确认地点。

## 技术边界

- 首个提供者采用高德 Android 地图 SDK，封装在 `CampusMapProvider` 接口后，避免业务模型直接依赖第三方类型。
- POI 搜索使用关键字、周边或多边形检索；地图点选后调用逆地理编码；缺失名称再读取附近 POI。
- `CampusPlace` 将增加稳定 ID、经纬度、用途、来源、置信度和确认状态；现有 `CampusZone` 仅作为紫金港兼容层。
- 路程优先调用步行／骑行路线服务；离线或失败时回退到已校正的分区估计。
- API Key 由开发者配置，并绑定 `com.sakata.focusflow` 与发布签名 SHA1，不要求最终用户提供 Key。

## 隐私与发布前置条件

- 地图 SDK 初始化前展示包含地图服务提供者的数据说明，并取得用户同意。
- 未同意时不初始化地图或搜索 SDK，继续使用内置目录和本地路线校正。
- 默认不申请定位权限；只有用户主动选择“使用我的位置”时再单独请求。
- 地图自动发现、点选与用户确认应分别记录来源，不把第三方搜索结果当作用户事实。

## 官方依据

- 高德 Android 地图 SDK 获取 Key：https://lbs.amap.com/api/android-sdk/guide/create-project/get-key
- POI 搜索：https://lbs.amap.com/api/android-sdk/guide/map-data/poi
- 逆地理编码：https://lbs.amap.com/api/android-sdk/guide/map-data/geo
- Marker 与拖拽／点击：https://lbs.amap.com/api/android-sdk/guide/draw-on-map/draw-marker
- 隐私合规接口：https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention
