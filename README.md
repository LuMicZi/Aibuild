# 🎮 Aibuild — Auto-Build Plugin for Paper / WorldEdit

> 基于 WorldEdit 的全自动建筑模板插件 | Author: **LuMickZi**
>
> 适用于 Paper 1.21+ ~ 26.1.x（其他版本未测试）

---

## ✨ 特性一览

| 功能 | 说明 |
|------|------|
| 📚 **模板管理** | 自动扫描 `plugins/Aibuild/schematics/` 下的 `.schem` / `.schematic` 文件 |
| 🖥️ **图形界面 (GUI)** | 54 格 GUI，分页浏览，点击附魔书即可建造 |
| 🏗️ **一键建造** | 命令行 `/aibuild build` 或 GUI 点击都能建造 |
| ↩️ **撤销支持** | 每次建造后自动记录区域，可一键撤销（GUI 红色陶瓦或命令） |
| 🌐 **多语言** | 内置英文 / 简体中文双语包，运行时可切换 |
| 🔐 **权限系统** | 细粒度权限（build / gui / undo / reload） |
| ⚡ **快速粘贴** | 使用 `EditSession.fastMode()` 避免大型建筑阻塞主线程 watchdog |

---

## 📦 快速开始

### 1. 前置要求

- **Java 21** 或更高版本
- **Paper 服务器**（1.21+ ~ 26.1.x 已验证）
- **WorldEdit 插件**（worldedit-bukkit-7.x.x.jar）

### 2. 安装

```bash
# 1. 构建（如果你是从源码构建）
mvn clean package

# 2. 复制到服务器
cp target/Aibuild-1.0.0.jar /path/to/server/plugins/

# 3. 重启服务器
```

首次启动后，插件会自动生成以下目录结构：

```
plugins/
└── Aibuild/
    ├── config.yml                    # 主配置文件（语言、文件夹路径）
    ├── languages/
    │   ├── messages_en.yml           # 英文语言包（可自行翻译/修改）
    │   └── messages_zh_CN.yml        # 中文语言包
    └── schematics/                   # 放你的 .schem / .schematic 文件
        ├── 浮空之城.schem
        ├── castle.schematic
        └── ...
```

### 3. 添加建筑模板

把 `.schem` 或 `.schematic` 文件拖到 `plugins/Aibuild/schematics/` 目录。  
支持通过 WorldEdit 创建：
```
//schematic save 模板名            # 保存选区
//schematic load 模板名             # 加载（可选，Aibuild 会自动读取）
```

然后执行：
```
/aibuild reload        # 重新扫描模板
```

---

## 🕹️ 游戏内使用

### 图形界面（推荐）

```
/aibuild gui           # 打开模板 GUI
/ab gui                # 简写
```

**GUI 布局**（54 格）：

```
┌────────────────────────────────────┐
│ 📖 📖 📖 📖 📖 📖 📖 📖 📖 │  顶部 45 格 = 附魔书 = 建筑模板
│ 📖 📖 📖 📖 📖 📖 📖 📖 📖 │  （点击即可建造）
│ 📖 📖 📖 📖 📖 📖 📖 📖 📖 │
│ 📖 📖 📖 📖 📖 📖 📖 📖 📖 │
│ 📖 📖 📖 📖 📖 📖 📖 📖 📖 │
├────────────────────────────────────┤
│ ←  🔴  📚     🔴  →│  最后一行：
│  ↑   ↑    ↑      ↑ │  ← = 上一页
│  ↑   ↑    ↑      ↑ │  🔴 = 撤销最后一次建造（红色陶瓦）
│  ↑   ↑    ↑      ↑ │  📚 = 模板信息
│  ↑   ↑    ↑      ↑ │  → = 下一页
└────────────────────────────────────┘
```

### 命令行

| 命令 | 权限 | 说明 |
|------|------|------|
| `/aibuild list` | `aibuild.use` | 列出所有模板 |
| `/aibuild build <模板名>` | `aibuild.build` | 在当前位置建造 |
| `/aibuild gui` | `aibuild.gui` | 打开图形界面 |
| `/aibuild undo` | `aibuild.undo` | 撤销上次建造 |
| `/aibuild reload` | `aibuild.reload` | 重新加载配置/模板 |
| `/aibuild language <en\|zh_cn>` | `aibuild.reload` | 切换显示语言 |
| `/aibuild help` | `aibuild.use` | 显示帮助 |

**所有命令的简写**：`/ab` 代替 `/aibuild`

### 示例工作流

```
# 1. 走到想建造的空地
/ab gui                       # 打开 GUI

# 2. 点击附魔书 → 建筑自动生成
#    （建造时会在聊天栏显示尺寸和坐标）

# 3. 不满意？点 GUI 底部的红色陶瓦（或输入 /ab undo）
/ab undo                      # 清除上次建造的区域

# 4. 重新放了新的 .schem 文件 → 重新扫描
/ab reload

# 5. 想切中文？
/ab language zh_cn
```

---

## 🔐 权限节点

默认所有权限仅 OP 可用。用权限管理插件（如 LuckPerms / PermissionsEx）授予玩家：

| 权限节点 | 控制内容 | 默认 |
|---------|---------|------|
| `aibuild.use` | 基础使用权限（总开关） | `op` |
| `aibuild.build` | 建造模板（放置方块） | `op` |
| `aibuild.gui` | 打开图形界面 | `op` |
| `aibuild.undo` | 撤销建造的权限 | `op` |
| `aibuild.reload` | 重新加载配置 / 切换语言 | `op` |

**LuckPerms 授权示例**：

```
# 给单个玩家建造权限
/lp user Notch permission set aibuild.build true
/lp user Notch permission set aibuild.gui true
/lp user Notch permission set aibuild.undo true

# 给"建筑师"权限组全套权限
/lp group builder permission set aibuild.use true
/lp group builder permission set aibuild.build true
/lp group builder permission set aibuild.gui true
/lp group builder permission set aibuild.undo true
```

---

## 🌐 语言与本地化

### 切换语言（运行时）

```
/aibuild language en          # 切换为英文
/aibuild language zh_cn       # 切换为中文
```

**变更会立即生效**，并保存到 `config.yml` 中（重启后仍保持）。

### 修改现有语言包

所有消息都在 `plugins/Aibuild/languages/` 下。你可以**直接修改这些文件**来定制文案：

```
plugins/Aibuild/languages/
├── messages_en.yml           # 可随意编辑
└── messages_zh_CN.yml        # 可随意编辑
```

编辑完成后 `/aibuild reload` 即可生效。

---

## ⚙️ 配置文件 (`config.yml`)

```yaml
# ========================================================
#  Aibuild Configuration
#  Author: LuMickZi
# ========================================================

# 显示语言: en = 英文, zh_cn = 简体中文
language: "en"

# 模板文件所在目录（相对于 plugins/Aibuild/）
schematic-folder: schematics
```

---

## 🏗️ 构建原理（技术说明）

**粘贴流程**：

1. 玩家执行 `/aibuild build X` 或点击 GUI 附魔书
2. `SchematicManager` 从磁盘加载 `X.schem`，通过 `SchematicFormat` 解析
3. 计算**对齐**：建筑底部中心对齐玩家脚下位置
4. 建立 `WorldEdit` → `EditSession(newEditSessionBuilder)`
5. **启用 `fastMode = true`**：跳过"读取现有方块"环节，避免超大建筑触发服务器 `watchdog` 超时（常见报错："Server has not responded for 10 seconds"）
6. `ClipboardHolder.createPaste(editSession).to(target)` → `Operations.complete()`
7. 记录 `(world, minX, minY, minZ, maxX, maxY, maxZ)` 到 `Map<UUID, BuildRecord>` 供撤销使用

**撤销流程**：

1. 从 `lastBuilds[玩家UUID]` 取出建造区域
2. 在同一世界中用 `EditSession` 遍历该长方体区域，每格设置为 `AIR`
3. 从 map 中清除该玩家的记录（一次撤销，不可重复）

---

## 🛠️ 从源码构建

```bash
# 克隆并构建
cd Aibuild/
mvn clean package

# 输出位置
target/Aibuild-1.0.0.jar
```

**依赖**（Maven 会自动下载）：

| 依赖 | 用途 |
|------|------|
| `io.papermc.paper:paper-api` | Bukkit/Paper 插件 API |
| `com.sk89q.worldedit:worldedit-core` + `:worldedit-bukkit` | 模板解析 + 编辑会话 |

---

## 📂 项目结构

```
Aibuild/
├── pom.xml                                   # Maven 构建配置
└── src/main/
    ├── java/com/aibuild/
    │   ├── Aibuild.java                      # 插件主类（启动、语言、配置）
    │   ├── manager/SchematicManager.java     # 核心：粘贴 + 撤销 + 模板列表
    │   ├── gui/SchematicGui.java             # GUI：附魔书 + 翻页 + 撤销按钮
    │   └── command/AibuildCommand.java       # 所有子命令处理
    └── resources/
        ├── plugin.yml                        # 插件描述 + 权限声明 + 命令声明
        ├── config.yml                        # 用户配置（language、文件夹）
        ├── messages_en.yml                   # 英文语言包
        └── messages_zh_CN.yml                # 中文语言包
```

---

## ❓ 常见问题

**Q: 服务器启动报 "Aibuild requires WorldEdit"？**  
A: 你的 `plugins/` 目录下没有 WorldEdit。安装 `worldedit-bukkit-7.4.x.jar` 后重启。

**Q: 建筑只生成一半或者位置不对？**  
A: 建筑对齐规则是**底部中心对齐玩家脚下**。如果你从 WorldEdit 导出时 origin 不在建筑底部中心，可能会有偏移。试试 `//schematic save` 时确认选区在你要的位置，或者用命令行建造后微调位置再复制。

**Q: 大型建筑粘贴时服务器卡死（"Server has not responded for 10 seconds"）？**  
A: 这是 Paper 的 watchdog 检测。当前版本已通过 `editSession.setFastMode(true)` 来避免读取现有方块。如果仍然卡住，说明你的单个模板超过 50 万方块，建议拆分为多个小模板分步粘贴。

**Q: 撤销功能能否多次撤销？**  
A: 目前是**一次撤销**（每次新建造会覆盖前一次记录，且撤销后清除记录）。如果需要多次撤销栈，请告知作者，可计划加入「保留最近 N 次记录」功能。

**Q: 能加第三方语言吗？**  
A: 当然可以！把 `messages_en.yml` 复制一份成 `messages_xx.yml`，翻译后发到 `plugins/Aibuild/languages/`，然后改 `config.yml` 里的 `language` 字段为对应值即可。

---

## 📝 更新日志

### v1.0.0

- ✅ 支持 Paper 1.21+ ~ 26.1.x
- ✅ GUI 54 格图形界面（分页）
- ✅ 命令行建造 / 撤销
- ✅ 中英双语（可运行时切换）
- ✅ 权限系统（细粒度控制）
- ✅ `EditSession.fastMode()` 快速粘贴
- ✅ 自动生成配置 + 语言文件

---

## 👤 作者

**LuMickZi**  
QQ: 507657354
