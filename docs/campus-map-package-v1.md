# 校园地点包 v1

FocusFlow 2.2 支持从“设置 → 通勤与地点”导入 UTF-8 JSON 地点包。导入地点会出现在课程编辑器中，其 `zone` 会用于课程空挡和校内路程估算。

```json
{
  "name": "紫金港自定义地点",
  "version": 1,
  "places": [
    { "name": "西1教学楼", "zone": "WEST_TEACHING", "kind": "教学楼" },
    { "name": "图书馆", "zone": "LIBRARY", "kind": "学习" }
  ]
}
```

可用 `zone`：

- `WEST_TEACHING`
- `EAST_TEACHING`
- `NORTH_TEACHING`
- `CHEMISTRY_LABS`
- `LIBRARY`
- `EAST_STADIUM`

规则：地点数量为 1–100 个；名称不能为空或重复；`version` 当前仅支持 1。地点包只描述用于排程的区域，不包含实时位置，也不会启用后台定位。
