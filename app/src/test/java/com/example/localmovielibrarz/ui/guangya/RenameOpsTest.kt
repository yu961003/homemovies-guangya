package com.example.localmovielibrarz.ui.guangya

import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import com.example.localmovielibrarz.cloudguangya.orderRenamesByDependency
import com.example.localmovielibrarz.cloudguangya.splitIndependentDependent
import com.example.localmovielibrarz.cloudguangya.uniqueTempName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameOpsTest {

    // ---- 字面模式 ----
    @Test fun `前N个字符`() =
        assertEquals("-123", RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.FirstN(6), ""))

    @Test fun `第M位后的N个字符`() =
        assertEquals("ABCDEF", RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.AfterPositionMN(6, 4), ""))

    @Test fun `倒数M位前的N个字符`() =
        assertEquals("ABCD-123", RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.BeforeEndPositionMN(4, 2), ""))

    @Test fun `XX之后全部_含标记`() =
        assertEquals("ABCDEF", RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.AfterStringAll("-", true), ""))

    @Test fun `XX未找到_原样返回`() =
        assertEquals("ABCDEF-123", RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.AfterStringAll("ZZ"), ""))

    @Test fun `插入到XX之后`() =
        assertEquals("ABCDEF-[HD]123", RenameOps.insert("ABCDEF-123", RenameInsertPosition.AfterString("-"), "[HD]"))

    // ---- 正则模式 ----
    @Test fun `正则XX删除中括号标记`() =
        assertEquals(
            "ABC-123",
            RenameOps.deleteOrReplace("ABC[HD]-123", RenameDeleteRange.SpecifiedString("\\[.*?\\]"), "", xxAsRegex = true)
        )

    @Test fun `正则保留XX删其后`() =
        assertEquals(
            "ABCDEF-",
            RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.AfterStringAll("-"), "$1", xxAsRegex = true)
        )

    @Test fun `正则前N字符带分组替换`() =
        assertEquals(
            "[AB]CDEF-123",
            RenameOps.deleteOrReplace("ABCDEF-123", RenameDeleteRange.FirstN(2), "[$1]", xxAsRegex = true)
        )

    @Test fun `正则插入到每处XX之后`() =
        assertEquals(
            "A-+B-+C",
            RenameOps.insert("A-B-C", RenameInsertPosition.AfterString("-"), "+", markerAsRegex = true)
        )

    @Test fun `非法正则返回null`() =
        assertNull(RenameOps.regexReplace("abc", "[", "x", false, false))

    @Test fun `引用不存在分组返回null`() =
        assertNull(RenameOps.regexReplace("abc", "(a)", "$2", false, false))

    @Test fun `正则分组交换`() =
        assertEquals("123-ABCDEF", RenameOps.regexReplace("ABCDEF-123", "(\\w+)-(\\d+)", "$2-$1", false, false))

    // ---- 模板 ----
    @Test fun `模板JSON往返`() {
        val t = BatchRenameTemplate(id = 1, name = "x", opType = 0, rangeIndex = 3, m = 6, n = 4)
        assertEquals(t, BatchRenameTemplate.fromJson(t.toJson()))
    }

    // ---- 第六轮 P1：非法字符误判 + fail-fast 阻塞批量操作 ----

    @Test fun `原名含非法字符时不阻塞批量操作`() {
        // 模拟文件名含双引号（服务端存量数据中存在此类名字）
        val files = listOf(
            mockFile("test\"file.mp4"),   // 原名含 "
            mockFile("normal.mp4")
        )
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.FirstN(4), "")
        val result = buildBatchRenameNames(files, config)
        // 两个文件都应有结果：继承的 " 原样保留（放行，不清洗、不拦截）
        assertNotNull(result.names)
        assertEquals(2, result.names!!.size)
        assertEquals("\"file.mp4", result.names!![0])   // test"file → "file（删前 4 个字符）
        assertEquals("al.mp4", result.names!![1])       // normal → al
    }

    @Test fun `批量中单个文件参数越界不阻塞其他文件`() {
        // AfterPositionMN 的 M 超过主名长度时该文件返回 null
        val files = listOf(
            mockFile("abcdefghijk.mp4"),  // 主名 11 位 ≥ M=10，正常
            mockFile("abc.mp4")           // 主名 3 位 < M=10，越界 → 跳过
        )
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.AfterPositionMN(10, 2), "")
        val result = buildBatchRenameNames(files, config)
        assertNotNull(result.names)
        assertEquals(1, result.skippedIndices.size)
        assertNotNull(result.names!![0])          // 第 1 个文件正常生成
        assertNull(result.names!![1])             // 第 2 个文件被跳过
    }

    @Test fun `全量失败时返回null`() {
        val files = listOf(mockFile("test.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.FirstN(0), "")  // n=0 → 参数非法
        val result = buildBatchRenameNames(files, config)
        assertNull(result.names)
        assertEquals(setOf(0), result.skippedIndices)
    }

    @Test fun `自定义正则替换串含非法字符时返回null`() =
        // P1-C：regexReplace 补校验后，replacement 含 ":" 直接拒绝
        assertNull(RenameOps.regexReplace("abc", "a", "a:b", false, false))

    @Test fun `自定义正则替换串含分组引用不受影响`() =
        // "$" 不在 ILLEGAL_NAME_CHARS 中，分组交换仍正常
        assertEquals("123-ABCDEF", RenameOps.regexReplace("ABCDEF-123", "(\\w+)-(\\d+)", "$2-$1", false, false))

    // ---- 第六轮 P2：多规则队列 ----

    @Test fun `多规则队列_包含匹配模式`() {
        val files = listOf(
            mockFile("ABC-video-01.mp4"),
            mockFile("FGH-video-02.mp4"),
            mockFile("OTHER-03.mp4")
        )
        val rules = listOf(
            RenameRuleEntry(
                config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("ABC"), ""),
                matchMode = RuleMatchMode.Contains("ABC")
            ),
            RenameRuleEntry(
                config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("FGH"), ""),
                matchMode = RuleMatchMode.Contains("FGH")
            )
        )
        val result = buildMultiRuleRenameNames(files, rules)
        assertEquals(2, result.renamePairs.size)
        assertEquals(1, result.unassignedFiles.size)  // OTHER-03 未匹配
        // ★ 第十轮 C-1 起 composeName 会清掉首尾 '-'/'.'：删掉 "ABC" 后的中间结果 "-video-01"
        //   被净化为 "video-01"（服务端拒绝 "-." 开头形态），故此处期望值不含前导 "-"。
        assertEquals("video-01.mp4", result.renamePairs[0].second)
        assertEquals("video-02.mp4", result.renamePairs[1].second)
    }

    @Test fun `多规则队列_已处理文件不被后续规则重复处理`() {
        val files = listOf(mockFile("ABCFGH.mp4"))
        val rules = listOf(
            RenameRuleEntry(
                config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("ABC"), ""),
                matchMode = RuleMatchMode.Contains("ABC")
            ),
            RenameRuleEntry(
                config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("FGH"), ""),
                matchMode = RuleMatchMode.Contains("FGH")
            )
        )
        val result = buildMultiRuleRenameNames(files, rules)
        // 文件先被规则 1 处理（删除 ABC → "FGH.mp4"），规则 2 不会再处理它
        assertEquals(1, result.renamePairs.size)
    }

    @Test fun `多规则队列_NewNaming序号跨规则接续`() {
        // v6：两条 NewNaming 规则的 {n} 自动接续，从根源避免跨规则重名
        val files = listOf(mockFile("A-1.mp4"), mockFile("A-2.mp4"), mockFile("B-1.mp4"))
        val rules = listOf(
            RenameRuleEntry(
                config = BatchRenameConfig.NewNaming("v{n}"),
                matchMode = RuleMatchMode.Contains("A-")
            ),
            RenameRuleEntry(
                config = BatchRenameConfig.NewNaming("v{n}"),
                matchMode = RuleMatchMode.Contains("B-")
            )
        )
        val result = buildMultiRuleRenameNames(files, rules)
        assertEquals(listOf("v01.mp4", "v02.mp4", "v03.mp4"), result.renamePairs.map { it.second })
    }

    @Test fun `多规则队列_不同规则生成同名时整体失败`() {
        // v6：NewNaming 接续堵住了序号撞名，但自定义正则仍可显式生成同名 → 必须整体失败
        // （CustomRegex 默认只作用于主名，需用 applyToExtension=true 才能让整名命中后统一为 same.mp4）
        val files = listOf(mockFile("a.mp4"), mockFile("b.mp4"))
        val rules = listOf(
            RenameRuleEntry(
                config = BatchRenameConfig.CustomRegex("^a\\.mp4$", "same.mp4", applyToExtension = true),
                matchMode = RuleMatchMode.Contains("a")
            ),
            RenameRuleEntry(
                config = BatchRenameConfig.CustomRegex("^b\\.mp4$", "same.mp4", applyToExtension = true),
                matchMode = RuleMatchMode.Contains("b")
            )
        )
        val result = buildMultiRuleRenameNames(files, rules)
        assertTrue(result.renamePairs.isEmpty())
        assertTrue(result.errors.any { it.contains("相同的新文件名") })
    }

    @Test fun `多规则队列_规则失败文件只计一次`() {
        // v6 回归用例：修复前失败文件会同时进入 skippedFiles 和 unassignedFiles（双重计数）
        val files = listOf(mockFile("abc.mp4"))
        val rules = listOf(
            RenameRuleEntry(
                config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.FirstN(0), ""),  // 参数非法 → 整体失败
                matchMode = RuleMatchMode.All
            )
        )
        val result = buildMultiRuleRenameNames(files, rules)
        assertTrue(result.renamePairs.isEmpty())
        assertEquals(1, result.skippedFiles.size)      // 计入 skipped
        assertEquals(0, result.unassignedFiles.size)   // 修复前此处错误地为 1
    }

    // ---- 第九轮 P0：生成名 trim 净化（服务端拒绝空格首尾名） ----

    @Test fun `删除前缀后首部空格被清除`() {
        val files = listOf(mockFile("[HD] Video.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("[HD]"), "")
        val result = buildBatchRenameNames(files, config)
        assertEquals("Video.mp4", result.names!![0]) // 而非 " Video.mp4"（服务端会拒绝）
    }

    @Test fun `删除尾段后主名尾空格被清除`() {
        val files = listOf(mockFile("Video HD.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("HD"), "")
        val result = buildBatchRenameNames(files, config)
        assertEquals("Video.mp4", result.names!![0]) // 而非 "Video .mp4"
    }

    @Test fun `trim后为空的文件被跳过`() {
        val files = listOf(mockFile(" ABC.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("ABC"), "")
        val result = buildBatchRenameNames(files, config)
        // 该批仅 1 个文件且唯一项被跳过 → 命中「全部跳过」契约：names 整体为 null，
        // 原因列表可通过 skippedIndices/skippedReasons 查看（而非 names[0]）。
        assertNull(result.names)
        assertEquals(setOf(0), result.skippedIndices)
        assertEquals("生成结果为空", result.skippedReasons[0])
    }

    // ---- 批次 6b：生成名完整校验 + 重名 auto-suffix ----

    @Test fun `校验_合法名通过`() {
        assertNull(validateGeneratedName("Video (1).mp4"))
        assertNull(validateGeneratedName("星野美忧-001.mp4"))
    }

    @Test fun `校验_拒绝空名与超长名`() {
        assertEquals("名称为空", validateGeneratedName("   "))
        assertTrue(validateGeneratedName("A".repeat(256) + ".mp4")?.contains("255") == true)
    }

    @Test fun `校验_拒绝空格与点首尾`() {
        assertEquals("名称不能以空格开头或结尾", validateGeneratedName(" Video.mp4"))
        // ★ E-1：点前空格（"Video .mp4"）此前被漏检，服务端会拒绝，现已补拦
        assertEquals("扩展名分隔点前后不能有空格", validateGeneratedName("Video .mp4"))
        assertEquals("扩展名分隔点前后不能有空格", validateGeneratedName("Video. mp4"))
        assertEquals("名称不能以点开头或结尾", validateGeneratedName(".hidden.mp4"))
        assertEquals("名称不能以点开头或结尾", validateGeneratedName("Video.mp4."))
    }

    @Test fun `校验_拒绝连续点与非法字符`() {
        assertEquals("名称不能包含连续点", validateGeneratedName("a..b.mp4"))
        assertEquals("名称包含非法字符", validateGeneratedName("a/b.mp4"))
        assertEquals("名称包含非法字符", validateGeneratedName("a:b.mp4"))
    }

    @Test fun `校验_拒绝系统保留名`() {
        assertEquals("名称是系统保留名", validateGeneratedName("CON.mp4"))
        assertEquals("名称是系统保留名", validateGeneratedName("COM1.mp4"))
        assertEquals("名称是系统保留名", validateGeneratedName("LPT9.txt"))
        assertNull(validateGeneratedName("CONFIG.mp4")) // 前缀相似但非保留名
    }

    @Test fun `auto-suffix_不冲突保持原名`() {
        assertEquals("Video.mp4", dedupeNameAgainst("Video.mp4", setOf("Other.mp4")))
    }

    @Test fun `auto-suffix_撞名补序号`() {
        assertEquals("Video (1).mp4", dedupeNameAgainst("Video.mp4", setOf("Video.mp4")))
        assertEquals("Video (3).mp4", dedupeNameAgainst("Video.mp4", setOf("Video.mp4", "Video (1).mp4", "Video (2).mp4")))
    }

    @Test fun `auto-suffix_无扩展名也补序号`() {
        assertEquals("Video (1)", dedupeNameAgainst("Video", setOf("Video")))
    }

    // ---- 第十轮 C-1：composeName 清首尾 -/. 残留 ----

    @Test fun `删除前缀后点横开头残留被清除`() {
        val files = listOf(mockFile("[组名]-.xxx.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("[组名]"), "")
        val result = buildBatchRenameNames(files, config)
        assertEquals("xxx.mp4", result.names!![0]) // 而非 "-.xxx.mp4"（服务端会拒绝）
    }

    @Test fun `尾部连字符残留被清除`() {
        val files = listOf(mockFile("xxx-HD.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("HD"), "")
        val result = buildBatchRenameNames(files, config)
        assertEquals("xxx.mp4", result.names!![0]) // 而非 "xxx-.mp4"
    }

    @Test fun `清理后为空的文件被跳过`() {
        val files = listOf(mockFile("-.mp4"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.FirstN(1), "")
        val result = buildBatchRenameNames(files, config)
        // 同样命中「全部跳过」契约：names 整体为 null（stem "-." → 清空 → 跳过）
        assertNull(result.names)
        assertEquals(setOf(0), result.skippedIndices)
        assertEquals("生成结果为空", result.skippedReasons[0])
    }

    // ---- 第十轮 C-2：validateGeneratedName 补 - 开头拦截 ----

    @Test fun `校验_拒绝以-开头`() {
        assertEquals("名称不能以 - 开头", validateGeneratedName("-video.mp4"))
        assertNull(validateGeneratedName("v-ideo.mp4")) // 中部 - 合法
    }

    // ---- 第十轮 A-2：dedupeNameAgainst 规范化比对 ----

    @Test fun `auto-suffix_大小写规范化命中同一键`() {
        // VIDEO.mp4 与 Video.mp4 应判为同名 → 补序号
        assertEquals("Video (1).mp4", dedupeNameAgainst("Video.mp4", setOf("VIDEO.mp4")))
    }

    @Test fun `auto-suffix_NFC变体命中同一键`() {
        // 组合字符与分解等价应判为同名（NFC 规范化）
        val nfc = "café.mp4"
        val nfd = "cafe\u0301.mp4"
        assertEquals("café (1).mp4", dedupeNameAgainst(nfc, setOf(nfd)))
    }

    // ---- 第十一轮 D-1：拓扑排序 + 环检测 ----

    @Test fun `拓扑排序_目标名撞后执行项旧名时被排到其后`() {
        val namesById = mapOf("idA" to "X.mp4", "idB" to "Y.mp4")
        val renames = listOf("idB" to "X.mp4", "idA" to "Z.mp4")   // B 目标名=X（A 旧名），B 在前
        val (ordered, ring) = orderRenamesByDependency(renames, namesById)
        assertEquals(emptyList<Pair<String, String>>(), ring)
        val aIndex = ordered.indexOfFirst { it.first == "idA" }
        val bIndex = ordered.indexOfFirst { it.first == "idB" }
        assertTrue("A 必须在 B 之前", aIndex >= 0 && bIndex >= 0 && aIndex < bIndex)
    }

    @Test fun `拓扑排序_互换名场景检测为环`() {
        val namesById = mapOf("idA" to "X.mp4", "idB" to "Y.mp4")
        val renames = listOf("idA" to "Y.mp4", "idB" to "X.mp4")   // A→B旧名, B→A旧名
        val (ordered, ring) = orderRenamesByDependency(renames, namesById)
        assertEquals(2, ring.size)
    }

    // ---- 第十三轮 G-1：依赖项拆分 splitIndependentDependent ----

    @Test fun `依赖拆分_目标名撞其他项旧名判为依赖`() {
        // B 目标名 = A 旧名，B 在前 → B 依赖（走临时名中转），A 独立（直改）
        val namesById = mapOf("idA" to "X.mp4", "idB" to "Y.mp4")
        val renames = listOf("idB" to "X.mp4", "idA" to "Z.mp4")
        val (independent, dependent) = splitIndependentDependent(renames, namesById)
        assertEquals(setOf("idA" to "Z.mp4"), independent.toSet())
        assertEquals(setOf("idB" to "X.mp4"), dependent.toSet())
    }

    @Test fun `依赖拆分_互换名双方均为依赖`() {
        // A→B旧名, B→A旧名：双方目标名都命中对方旧名 → 均走临时名两阶段
        val namesById = mapOf("idA" to "X.mp4", "idB" to "Y.mp4")
        val renames = listOf("idA" to "Y.mp4", "idB" to "X.mp4")
        val (independent, dependent) = splitIndependentDependent(renames, namesById)
        assertTrue(independent.isEmpty())
        assertEquals(2, dependent.size)
    }

    @Test fun `依赖拆分_目标名等于自身旧名按独立直改`() {
        // 等价无改名（newName == oldName）不触发中转，避免无意义 churn
        val namesById = mapOf("idA" to "X.mp4")
        val (independent, dependent) = splitIndependentDependent(listOf("idA" to "X.mp4"), namesById)
        assertEquals(listOf("idA" to "X.mp4"), independent)
        assertTrue(dependent.isEmpty())
    }

    @Test fun `依赖拆分_无冲突目标全部独立`() {
        val namesById = mapOf("idA" to "X.mp4", "idB" to "Y.mp4")
        val (independent, dependent) = splitIndependentDependent(
            listOf("idA" to "P.mp4", "idB" to "Q.mp4"), namesById
        )
        assertEquals(2, independent.size)
        assertTrue(dependent.isEmpty())
    }

    // ---- 第十三轮 G-1：临时名生成 uniqueTempName（零冲突面） ----

    @Test fun `临时名_不撞现有名且保留扩展名`() {
        val occupied = mutableSetOf("x.mp4".renameNameKey())
        val temp = uniqueTempName("A.mp4", occupied)
        assertNotNull(temp)
        assertTrue(temp!!.endsWith(".mp4"))
        assertTrue(temp.renameNameKey() !in occupied)
    }

    @Test fun `临时名_撞名自动递增`() {
        val occupied = mutableSetOf<String>()
        // 预占 tmp-ren-1-A.mp4，应跳过并生成 tmp-ren-2-A.mp4
        occupied.add("tmp-ren-1-A.mp4".renameNameKey())
        val temp = uniqueTempName("A.mp4", occupied)
        assertEquals("tmp-ren-2-A.mp4", temp)
    }

    @Test fun `临时名_无扩展名形态`() {
        val temp = uniqueTempName("README", mutableSetOf())
        assertEquals("tmp-ren-1-README", temp)
    }

    // ---- 第十三轮 G-3②：不可见空格显形 ----

    @Test fun `可视化_首尾空格显形`() {
        assertEquals("ABC-XXX␠.restored", visualizeInvisibleChars("ABC-XXX .restored"))
        assertEquals("␠hidden␠", visualizeInvisibleChars(" hidden "))
    }

    // ==================== 第十四轮（需求 4）：批量重命名整体复审 ====================
    //
    // 用户反馈：「大量选择文件后，删除字符串会提示有重名，但怎么可能呢？去掉的都是一样的
    // 字符串，去之前没重名，去掉就重名了？这不是离谱吗？」
    //
    // 根因三条（均已修复，逐条回归）：
    //   B1 conflictSet 语义混淆：目录既有名 与 本批同伴新名 混入同一集合，
    //      且没经过 fileId 身份判定 → 同批同伴互相加 (n) 序号。
    //   B2 预览层不做消解、执行层做 → 所见非所得。
    //   B3 预检用精确字符串、执行用 renameNameKey → 两套判定标准。
    // 修复：抽出 BatchNameResolver 作为预览/执行共用的唯一消解器 + sortedForResolve 统一顺序。

    // ---- B1：批内收敛同名，绝不能被同伴加序号 ----

    @Test fun `批内消解_收敛同名不加序号且结果确定`() {
        // 4 个版本删掉各自不同的画质标记 → 收敛为同一主名（这是正常语义，不是错误）
        val entries = listOf(
            "id0" to "ABC-100.mp4",
            "id1" to "ABC-100.mp4",
            "id2" to "ABC-100.mp4",
            "id3" to "ABC-100.mp4"
        )
        val resolved = BatchNameResolver.resolve(entries, emptyList())
        // 第 1 项保持干净名，其余按顺序补最小必要序号（而非 4 项全被加）
        assertEquals("ABC-100.mp4", resolved[0])
        assertEquals("ABC-100 (1).mp4", resolved[1])
        assertEquals("ABC-100 (2).mp4", resolved[2])
        assertEquals("ABC-100 (3).mp4", resolved[3])
        assertEquals(4, resolved.toSet().size)   // 两两不同，无覆盖风险
    }

    @Test fun `批内消解_声明同名才加序号而非按同伴新名连坐`() {
        // 3 个文件期望的新名两两不同 → 一个序号都不该加（旧实现会因 conflictSet 累积而误加）
        val entries = listOf("a" to "A.mp4", "b" to "B.mp4", "c" to "C.mp4")
        assertEquals(listOf("A.mp4", "B.mp4", "C.mp4"), BatchNameResolver.resolve(entries, emptyList()))
    }

    @Test fun `批内消解_确定性_输入顺序经排序后结果一致`() {
        val names = listOf("z9" to "N.mp4", "a1" to "N.mp4", "m5" to "N.mp4")
        val one = BatchNameResolver.resolve(names, emptyList())
        val two = BatchNameResolver.resolve(names.reversed(), emptyList())
        assertEquals(one, two)
    }

    // ---- 目录既有文件撞名：该加序号时必须加 ----

    @Test fun `批内消解_目录既有同名时才加序号`() {
        val entries = listOf("a" to "Movie.mp4")
        assertEquals(listOf("Movie.mp4"), BatchNameResolver.resolve(entries, listOf("Other.mp4")))
        assertEquals(listOf("Movie (1).mp4"), BatchNameResolver.resolve(entries, listOf("Movie.mp4")))
    }

    @Test fun `批内消解_目录已有base和序号时跳到下一个空位`() {
        val entries = listOf("a" to "Movie.mp4")
        assertEquals(
            listOf("Movie (2).mp4"),
            BatchNameResolver.resolve(entries, listOf("Movie.mp4", "Movie (1).mp4"))
        )
    }

    @Test fun `批内消解_目录冲突与批内冲突叠加时连续分配`() {
        val entries = listOf("a" to "Movie.mp4", "b" to "Movie.mp4", "c" to "Movie.mp4")
        assertEquals(
            listOf("Movie (1).mp4", "Movie (2).mp4", "Movie (3).mp4"),
            BatchNameResolver.resolve(entries, listOf("Movie.mp4"))
        )
    }

    // ---- B3：大小写 / NFC 统一走 renameNameKey ----

    @Test fun `批内消解_大小写不敏感判定冲突`() {
        assertEquals(listOf("movie (1).mp4"), BatchNameResolver.resolve(listOf("a" to "movie.mp4"), listOf("MOVIE.MP4")))
        assertEquals(listOf("X.mp4", "x (1).MP4"), BatchNameResolver.resolve(listOf("a" to "X.mp4", "b" to "x.MP4"), emptyList()))
    }

    @Test fun `批内消解_无扩展名与多点名保持正确形态`() {
        assertEquals(listOf("NoExt", "NoExt (1)"), BatchNameResolver.resolve(listOf("a" to "NoExt", "b" to "NoExt"), emptyList()))
        assertEquals(listOf("A.B.MKV", "A.B (1).MKV"), BatchNameResolver.resolve(listOf("a" to "A.B.MKV", "b" to "A.B.MKV"), emptyList()))
    }

    // ---- B2：预览层不再因「收敛同名」直接拒绝执行 ----

    @Test fun `预览_收敛同名不再fail_fast而是给出可执行结果`() {
        // 旧行为：names == null + error「生成结果存在重名」→ 用户无法执行，且完全无法理解
        val files = listOf(
            mockFile("ABC-100[HD].mp4", "t0"),
            mockFile("ABC-100[4K].mp4", "t1"),
            mockFile("ABC-100[SD].mp4", "t2")
        )
        val config = BatchRenameConfig.CustomRegex("\\[(HD|4K|SD)\\]", "")
        val result = buildBatchRenameNames(files, config)
        assertNotNull("收敛同名是正常语义，不应整体失败", result.names)
        assertEquals(3, result.names!!.size)
        assertEquals(3, result.names!!.toSet().size)          // 全部不同名，不会互相覆盖
        assertTrue("应有一项保留干净名", result.names!!.contains("ABC-100.mp4"))
    }

    @Test fun `预览_删同一字符串场景不再误报重名`() {
        // 删掉同样的 " hhd800" —— 用户描述的正是这种「去掉的都是一样的字符串」
        val files = (1..5).map { mockFile("ABC-%03d hhd800.mp4".format(it), "id$it") }
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString(" hhd800"), "")
        val result = buildBatchRenameNames(files, config)
        assertNotNull(result.names)
        assertEquals(
            listOf("ABC-001.mp4", "ABC-002.mp4", "ABC-003.mp4", "ABC-004.mp4", "ABC-005.mp4"),
            result.names
        )
        assertTrue("无目录冲突时不应有任何自动加序号", result.autoSuffixedIndices.isEmpty())
    }

    // ---- B2：预览 == 执行（所见即所得） ----

    @Test fun `预览与执行结果一致_收敛同名场景`() {
        val files = listOf(
            mockFile("ABC-100[HD].mp4", "t0"),
            mockFile("ABC-100[4K].mp4", "t1"),
            mockFile("ABC-100[SD].mp4", "t2")
        )
        val config = BatchRenameConfig.CustomRegex("\\[(HD|4K|SD)\\]", "")
        val preview = buildBatchRenameNames(files, config)

        // 模拟执行层：同样经 sortedForResolve + BatchNameResolver
        val entries = sortedForResolve(files.mapIndexed { i, f -> Triple(f.fileId, f.name, preview.names!![i]!!) })
        val executed = BatchNameResolver.resolveWithFlags(entries, emptyList()).associate { it.fileId to it.final }

        files.forEachIndexed { i, f ->
            assertEquals(
                "预览与执行必须一字不差（${f.name}）",
                preview.names!![i],
                executed[f.fileId]
            )
        }
    }

    @Test fun `预览与执行结果一致_目录撞名场景`() {
        val files = listOf(mockFile("Old.mp4", "d0"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("Old"), "New")
        val preview = buildBatchRenameNames(files, config, dirExistingNames = listOf("New.mp4"))
        assertEquals(listOf("New (1).mp4"), preview.names)
        assertTrue(preview.autoSuffixedIndices.contains(0))

        val entries = sortedForResolve(listOf(Triple("d0", "Old.mp4", "New.mp4")))
        val executed = BatchNameResolver.resolveWithFlags(entries, listOf("New.mp4"))
        assertEquals(preview.names!![0], executed[0].final)
    }

    @Test fun `自动加序号项数在预览层可见`() {
        val files = listOf(mockFile("Old.mp4", "d0"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("Old"), "New")
        val result = buildBatchRenameNames(files, config, dirExistingNames = listOf("New.mp4"))
        assertEquals(1, result.autoSuffixedIndices.size)
        assertTrue("提示文案应说明会自动加序号", result.error?.contains("自动加序号") == true)
    }

    @Test fun `跳过项仍保持null且不参与消解`() {
        // 第 2 个文件 FirstN(0) 参数非法被跳过，不应影响第 1 个文件的消解
        val files = listOf(mockFile("aaa.mp4", "k0"), mockFile("bbb.mp4", "k1"))
        val config = BatchRenameConfig.DeleteReplace(RenameDeleteRange.SpecifiedString("bbb"), "")
        val result = buildBatchRenameNames(files, config)
        assertNotNull(result.names)
        assertEquals(1, result.skippedIndices.size)
        assertNotNull(result.names!![0])
        assertNull(result.names!![1])
    }

    private fun mockFile(name: String, fileId: String = name): GuangyaFileItem =
        GuangyaFileItem(
            fileId = fileId,
            name = name,
            isDir = false,
            size = 0L,
            fileType = null,
            gcid = null,
            parentId = "test-parent"
        )
}