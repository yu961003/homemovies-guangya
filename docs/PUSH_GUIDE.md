# 推送到 GitHub 仓库 — 执行清单

# 目标仓库: https://github.com/yu961003/homemovies-guangya（公开，匿名可读）
# 背景: 本沙箱无 GitHub 写令牌，无法代表你完成推送；命令在你本机执行。

# ---- 现状（2026-10-26 核对 GitHub 上的实际状态）----
# 仓库 main 的当前形态是「仓库根放一个 zip，CI 解压后构建」，不是源码直推：
#   .github/workflows/build.yml                   <- CI（AssembleDebug）
#   homemovies_guangya_fixed_20261026.zip          <- 源码包，被 build.yml 解压后构建
#   README.md
# 所以日常发版只需要「换 zip」，不要在仓库里同时塞源码和 zip：
#   build.yml 的 Unzip 步骤会把 zip 内容铺到仓库根，
#   源码直接提交进仓库只会让两边各留一份、以谁为准说不清。

# ---- 方式 A: 只更新源码包（日常发版，推荐）----
# 用 GitHub 网页删掉旧的 homemovies_guangya_fixed_20261026.zip，
# 再上传 deliver/homemovies_guangya_fixed_20261026_ci.zip 并改回同一个文件名。
# 一次提交里删旧 + 传新，避免仓库根同时出现两个 zip。

# ★ 为什么必须「删旧 + 改同名」：
#   build.yml 用 `ls *.zip | head -n 1` 取第一个 zip。按字节序，
#   homemovies_guangya_fixed_20261026.zip（"." = 0x2E）排在
#   homemovies_guangya_fixed_20261026_ci.zip（"_" = 0x5F）前面，
#   两个同时存在会一直用旧包。已实测确认，加前缀也绕不过（"." 永远最小）。

# ---- 方式 B: 用 git 推（需本机已登录 GitHub）----
# git clone https://github.com/yu961003/homemovies-guangya.git
# cd homemovies-guangya
# cp /path/to/homemovies_guangya_fixed_20261026_ci.zip homemovies_guangya_fixed_20261026.zip
# git add -A && git commit -m "src: 20261026_ci（批量刮削修复 + 四模块增强 + CI 修复）"
# git push

# ⚠️ 不要对 main 用 `git push -f`：仓库的 .github/workflows/build.yml 是仓库侧资产，
#    本地 clone 若没有它，强推会把远端 CI 文件一起抹掉。

# ---- CI 修复（本次的重点，与 zip 无关）----
# run 40253956078 失败在 `Setup Android SDK` 步骤：
#   Failed to find package 'tools'
# 根因是 setup-android@v3 的默认 packages = 'tools platform-tools'，
# 而 legacy tools 包自 cmdline-tools 16.0 起已从 SDK 仓库移除。
# 修法（改仓库里的 build.yml，只动 SDK 那一处）：
#   - name: Setup Android SDK
#     uses: android-actions/setup-android@v3
#     with:
#       packages: 'platform-tools platforms;android-35 build-tools;35.0.0'
#       accept-android-sdk-licenses: true
# 现成文件：deliver/ci-fix/build.yml（完整）或 deliver/ci-fix/build.yml.patch（补丁）。
# 详细根因、验收标准与后续失败分流见 docs/CI-SETUP.md。

# 注意:
#   - 写操作需要认证（PAT 或已登录 gh）。
#   - 本工程尚未在 CI 上跑通过 assembleDebug（9 月那次 success 是旧代码），
#     SDK 步骤修好后，构建本身仍可能暴露编译错误，按 docs/CI-SETUP.md §6 分流。