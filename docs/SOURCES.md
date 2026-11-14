# 来源与实现依据

- [UX is Fine — Motion Services](https://www.uxisfine.com/motion-services)：重点参考用户指定的 HUD elements 视频的分层出现、细线延伸、焦点迁移与节点点亮。页面八段原视频已按完整时间序列检查，主参考过渡另作密集帧检查。详细时间点、取舍和实现映射见 [MOTION-DESIGN.md](MOTION-DESIGN.md)。仅借鉴运动规律，不分发参考视频或复用游戏美术。

- [用户提供的正式设计](https://ucndr9srcy1r.feishu.cn/wiki/FbjuwwoHEiTtovktnzhcmrpfnv3)：玩家中心仿射切片 `world(q)=origin+Bθq`，旋转时调整 origin 保持玩家四维位置；Z/W 方向固定，虚拟平移只沿切片地面方向。
- `core/Geometry4.kt`、`FourthwardGame.kt`、`TutorialLevels.kt`：本项目原创真实半空间/超球求交、胶囊体碰撞与六关教程实现。渲染器只消费真实截面，旗标仅注释已相交的目标。
- 用户的六面墙要求与 `core/TutorialLevels.kt`：第三关改为四周、顶部和底部共六面墙，封闭初始三维切片中的体积，保留沿第四维绕行后返回内部的真实通路。底面上缘 Z = −0.5、顶面下缘 Z = 2.8，所有墙在 Y 方向半厚度为 0.65；闭合面、完整目标区域、正常站立与顶底碰撞均有回归测试。
- 用户的现实走动碰撞要求与 `core/FourthwardGame.kt`：现实地面走动使用连续胶囊体碰撞，求得允许的位移后，用仿射切片原点补偿被拒绝的位移。保留原始 OpenXR 头部/双眼位姿、头部旋转与手部坐标，使游戏位置停在墙前；被拒绝的距离不累积，短距离后退立即有效。
- `core/LevelCatalog.kt`、`core/LevelProgress.kt`、`vr/LevelProgressStore.kt` 与 `vr/VrGameBridge.kt`：六关分为“入门教程”（1–3）和“四维几何体”（4–6）两组，按稳定 ID 保存已完成进度，前一关完成后解锁下一关，选择入口统一校验解锁状态。完成进度保存在本机 SharedPreferences，重试、重玩和重新启动不会清除。原三关 ID 和存档格式不变，覆盖安装后保留原有完成记录；已完成前三关时解锁第四关，第五、六关继续按顺序解锁。未知 ID 或不连续的完成数据不会解锁后续关卡。
- `core/PalmPoke.kt`、`vr/VrUiRenderer.kt` 与 `core/InputState.kt`：同一面板布局驱动绘制和物理点按。游戏菜单为四个主要按钮；讲解页为三个步骤按钮和关卡列表入口；列表每页为三行关卡、返回与左右分组箭头；行按钮代表当前页面的行槽，关卡项目保留全局编号。锁定项不可点击，换页后重新抬指才能触发。L 打开列表，Esc 返回，列表打开时数字 1–9 按全局编号选择已解锁关卡，[ / ] 切换上一组/下一组，Tab/Shift+Tab 与 Enter 选择并执行。
- `core/LessonTutorial.kt`、`vr/LessonSceneBuilder.kt` 与 `vr/GeometryLessonAnalogy.kt`：原创的六课 29 步立体讲解。前三课的二维角色、竖直平面、有限厚度的三维墙、真实平面与盒体截面及连续合法移动路径，均由确定性几何数据生成；后三课并排展示二维观察三维的线框模型与对应的四维物体三维截面；同步使用一个角度，左侧亮色真实二维截面与右侧真实三维截面有相同的半径或宽度变化规律。左侧完整三维线框仅属于外部教学模型，右侧没有四维投影。模型固定在世界空间供 6DoF 观察，前三课的旋转以角色为轴且不平移角色，每步动画结束后保持场景，只有用户操作才会前进或进入游戏。
- `core/GeometryExhibits.kt` 与 `core/FourthwardGame.kt`：三种固定四维几何体共用真实截面算法。偏离中心的四维球在 0°、45°、135° 分别呈现小球、最大球与空截面；四维正方体在 0°、45°、90° 呈现正方体、横向拉长的长方体与正方体；两个形状仅在 Y 方向长度不同的盒体在 0° 有相同截面，45° 与 90° 显示差异。所有演示只改变一个 θ，不使用四维投影。游戏按顺序校验实际截面与目标角度；第 4–6 关观察阶段使用固定玩家位置，忽略虚拟位移并通过仿射原点抵消真实头部 XYZ 平移，保持入关眼高和原点不变，保留转头、立体视图与手势旋转，并要求连续有效观察约 0.8 秒；第三次观察结束立即通关并保存，后三关不设置旗帜或走向终点的额外要求；N 进入下一课。失焦或关卡列表开启时不推进观察任务。
- `vr/LessonNarration.kt`、`tools/generate-fish-narration.py` 与 `app/src/main/assets/narration/manifest.json`：六课的 29 段中文旁白使用用户指定的 Fish Audio 社区音色[女管理员](https://fish.audio/m/1bf1dee221e24db79346a1a35c3343a3/)（发布者 Bravesermen Ten-t），模型 `s2.1-pro-free`、`[calm]`、语速 1.0。用户确认第三关开头试听后，按同样参数生成原三课素材。后三课的 13 段音频已按新的左右对照讲稿重新生成，沿用同样音色与参数，共约 178 秒，已通过摘要校验与非静音 PCM 解码检查；原有 16 段音频未重新生成。文字直接读取 `LessonTutorial.kt`，口语化改写与字幕同步；资产记录音色、模型、参数和文本/音频/请求摘要，密钥不保存到仓库或 APK。Android MediaPlayer 播放随包的 AAC 音频；旧 Tingting 仅保留在本机生成工具中。
- Fish Audio [TTS 接口](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)与[表达控制](https://docs.fish.audio/developer-guide/core-features/emotions)：JSON 请求、显式模型头、音色 ID、WAV 导出和方括号语气提示。实际响应使用流式 WAV 占位长度；工具按收到的 PCM 数据重写文件头，再转成 128 kbit/s AAC / M4A。声音的最终听感以用户试听反馈为准。
- [Khronos OpenXR SDK](https://github.com/KhronosGroup/OpenXR-SDK)与 [OpenXR SDK Source](https://github.com/KhronosGroup/OpenXR-SDK-Source)：公开 OpenXR 头文件、Loader 和 Android 原生双眼应用参考。本项目使用 `org.khronos.openxr:openxr_loader_for_android:1.1.65`，NDK 28.2.13676358、CMake 3.22.1、OpenGL ES 3。
- 当前 `AndroidManifest.xml`、`vr/OpenXrActivity` 与 `cpp/openxr_main.cpp`：活动入口是原生 OpenXR；使用运行时头部与双眼位姿、OPAQUE 合成和应用背景，不初始化 Spatial Stage。优先 OpenXR STAGE 参考空间（物理地面坐标），必要时使用 LOCAL；这里的 OpenXR 参考空间名称不等于 PICO Spatial Stage 应用模式。运行时仍拥有系统安全边界/追踪提示。
- `vr/VrSceneBuilder.kt`、`VrUiRenderer.kt`、`VrRenderFrame.kt`：CPU 精确场景三角形和 Canvas ARGB 图集通过 JNI 送入 GLES。界面采用半透明着色平面，各页面的按钮独立进行真实 Z 位移；不是系统玻璃合成或背景模糊。HUD 关闭世界深度遮挡而保留双眼空间深度。
- [ak-ui](https://github.com/YunYouJun/ak-ui)及[设计语言](https://github.com/YunYouJun/ak-ui/blob/master/skills/ak-ui/references/design-language.md)：仅借鉴中性表面、动作信号色和简洁层级，以 accent 强度适配原生 VR；不引入 CSS/网页运行时或游戏资产。此前 [Ark UI Skill](https://github.com/Brandon030722/ark-ui-skill) 的 Endfield 配色是项目色板的历史参考。
- 官方模拟器[键盘模式开关](https://github.com/Pico-Developer/PICO-Emulator-qemu/blob/main/android/android-ui/modules/aemu-ui-qt/src/android/skin/qt/left-tool-window.cpp#L94)与[事件路由](https://github.com/Pico-Developer/PICO-Emulator-qemu/blob/main/android/android-ui/modules/aemu-ui-qt/src/android/skin/qt/emulator-qt-window.cpp#L1497)：左工具栏键盘模式与底部手部模式独立；此前 OpenXR 原生按键路径已通过 ADB 面板与前三关验证。
- 历史 Spatial 实现来自 PICO CLI 0.7.0 模板与 Spatial SDK 6.2.8；[官方 Spatial 示例](https://github.com/Pico-Developer/PICO-Spatial-SDK-Samples)及 Core/Tracking/UI API 曾用于旧版实体、手追踪、原生玻璃与 `zOffset`。这些来源说明旧目录，不代表当前活动后端仍使用 Spatial 运行方式。
- [PICO Emulator 使用说明](https://developer.picoxr.com/document/pico-cli/emulator-guide/)：模拟器不能替代真实空间、佩戴舒适度和硬件交互验收。本项目将模拟器 OpenXR 验证与真实双手、头显和控制器验收分别记录。

- 用户确认的后三关方案与 `core/ExpeditionLevels.kt`、`ExpeditionDefinition.kt`：新增“交错门廊、隐藏回廊、信标归航”，作为第三组“四维寻路”（7–9）。四维建筑具有八个相互重叠的边界面，错位门、走廊连接和信标均为静态真实四维几何；玩家以同一个 θ 选择切片并导航。完成由真实路标接触与最终目标共同决定，不以角度口令通关。
- `core/FourthwardGame.kt`、`vr/VrGameBridge.kt`：记录当前游玩中已到达的路标及实际有效脚部位置、角度，重试时相对最新原始头显位姿重建切片。H 与掌心菜单“提示”显示三级任务线索；T 返回最近路标。第七、八关顺序记录路标，第九关两座信标任意顺序。仍使用稳定关卡 ID 的原有逐关解锁存档；前六课讲解与语音资产不变，新寻路关直接开始探索。
- `core/Geometry4.kt` 的可选 Box4 颜色与 `vr/VrSceneBuilder.kt`：颜色用于门槛和连接处的实体四维薄片。路标标记只附着于当前实际相交的超球截面，到达后改变菱形填充；隐藏方向的路标不投影出来。

第三方 SDK、Loader、Android、Kotlin、NDK 等依赖遵循各自许可证。SDK 源码仅用于核对 API，不复制分发。

此前三关的现实碰撞与关卡菜单版本通过 135 项 JVM 测试及构建/lint，并在模拟器验证真实 HMD 撞墙停住、持续前进被抵消、立即后退、三关顺序解锁与重启保存；详见 README 的此前版本验证记录。这些记录不代表新增第四至第六关或新增旁白已经完成原生验收。

此前 Fish 旁白版本的构建、111 项 JVM 测试、9 项 Fish 生成工具测试及 lint 通过，测试零失败、零错误、零跳过。讲解与六面墙的核心测试记录在 `.omo/evidence/six-faces-lessons-core/`，模型几何测试记录在 `.omo/evidence/lesson-scene/`。Fish 请求、缓存、错误脱敏和失败保护测试见 `.omo/evidence/fish-adapter/`；16 段 Fish 音频的解码和原生播放检查见 `.omo/evidence/fish-narration/`，先前 Tingting 记录保留在 `.omo/evidence/lesson-narration/`。模拟器原生 OpenXR 已验证逐步运行全部 16 步、回退、重播、冻结观察、失焦暂停/恢复及 MediaPlayer 播放；三关均可通关，第三关不旋转绕侧面仍被阻挡。此前完整 6DoF 位姿矩阵与双手输入验证边界见 README。真实硬件、双手触碰精度和头显音频听感仍需实测。

## 章节树与运动扩展

- 用户最新设计：移动章含原1–3、7–9；辨认章含原4–6；运动章先并列类比教学、再实际观察，随后追光/漂游之间/错峰门廊。完成移动章同时解锁两个互不依赖的分支；章内独立编号。
- `LevelCatalog`、`LevelProgress.forLevels`、`LevelProgressStore.loadLevels`：底层旧ID与索引保留，显示/前进按章节顺序，已完成ID迁移保留，未完成关卡受章节先决条件与本章前关约束。`VrUiRenderer` / `PalmPanelLayout` / `VrGameBridge` 共用树形节点和六格章节按钮布局，独立纹理与真实悬停深度。
- `MotionStudies` / `MotionLessonAnalogy`：正穿和斜穿超球的解析截面、倾斜立方盒与长盒的真实多面体求交，两侧共用四维中心运动的低维类比。只改变中心，不以动画缩放模拟截面。运动教程自由移动；辨认教程固定观察机制保留。
- `MotionPuzzles` / `FourthwardGame.tickWorld`：引路球到路标等待、八秒周期漂游块和错峰开口；运动实体用固定大小和基底的平移扫掠体检测，接近玩家则停止时钟，路标保存/恢复位置、角度和运动时间。
- 新增12段运动讲解沿用已确认Fish Audio音色与生成参数；原29段文本和音频不变，全部41段摘要校验。

## 关卡依赖路线与界面细化

- 用户提供的路线图仅作为依赖排列规则：前置纵向排列、独立路径并排；没有沿用其蓝底及红黄绿大色块。
- 统一 `LevelDependencyGraph` 读取 `LevelProgress.requirementsForLevels`，只在绘图时约简冗余连接。全部十五关直接入图、六层视窗上下浏览；此前章节树和六格页面已由此替代，稳定 ID 和存档门槛保留。
- 核对 ak-ui 版本 `ae5d2bd310844f9a75e3130c2055ea0f805caa86` 的 [card](https://github.com/YunYouJun/ak-ui/blob/master/src/scss/_ak-card.scss)、[button](https://github.com/YunYouJun/ak-ui/blob/master/src/scss/_ak-button.scss)、[tokens](https://github.com/YunYouJun/ak-ui/blob/master/src/scss/_tokens.scss)、[variables](https://github.com/YunYouJun/ak-ui/blob/master/src/scss/_variables.scss)：采用纸白/炭灰层级、暖黄色功能焦点、少量橙色标识、轻微切角、细边线与斜纹。绘制为原生 Canvas 图集及独立三维面片，未复制网页或游戏图像。

## 教程主线与可选挑战修订

- 用户修正关卡依赖：九个教程必须依次完成，游玩关卡为可选支线。移动三挑战在 1-3 后并行解锁，运动三挑战在 3-3 后并行解锁；挑战不阻塞后续教程。
- `LevelCatalog` 统一教程分类、完整教程前缀与主线后继；`LevelProgress` 和 `LevelDependencyGraph` 共用这些前置条件。图上主线纵向，挑战向右平行展开；同章编号与旧存档稳定 ID 保留。
- 「交错门廊」移除临近终点的第二路标，保留金色路标与两道门的几何，任务末段引导直接抵达出口。
