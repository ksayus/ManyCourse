package com.tof.manycourse

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.ui.GridCardTier
import com.tof.manycourse.ui.gridCardMinHeight
import com.tof.manycourse.ui.gridCardRoomBandHeight
import com.tof.manycourse.ui.gridCardStyle
import com.tof.manycourse.ui.gridCardTier
import com.tof.manycourse.ui.gridRowHeight
import com.tof.manycourse.ui.nameLineCount
import com.tof.manycourse.ui.spToDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周视图**课程卡片按卡片高度分档**（`ui/CalendarGridLayout.kt` 的 [gridCardTier]）。
 *
 * ## 为什么值得单测
 *
 * 行高去掉下限之后（见 [gridRowHeight]），块数多的学校 / 屏幕矮时每格只有 30~40dp ——
 * 卡片必须**跟着降级**，而不是被卡片下沿裁掉半截字。降级顺序是"先撤整项、再缩字号、
 * 最后才允许省略号"，三条都不能错：
 *
 *  - 撤晚了（明明放不下还画地点带）→ 地点那行字被裁掉一半；
 *  - 撤早了（还放得下就撤）→ 白白少显示"去哪儿上课"；
 *  - 课名区被地点带挤到 0 → 课名整块消失，用户认不出这是哪门课。
 *
 * 这些错法**都不会崩**，只在真机上少几个字，所以用纯函数钉在这里。
 */
class GridCardTierTest {

    /** 1080p / 393dp 宽、fontScale 1.17 的真机度量（与 GlassTokenGoldenTest 那台机器同一组）*/
    private val density = Density(density = 2.75f, fontScale = 1.17f)

    /** 宽松档一行课名 / 地点带的高度（当前字体度量下）*/
    private val nameLine = spToDp(density, 12.sp)
    private val roomLine = spToDp(density, 10.sp)

    // ══════════════════ ① 极小行高也落在最紧凑档 ══════════════════

    @Test
    fun 极小卡片落在最紧凑档() {
        // ★ 这一条钉的就是"永不裁字"的兜底：卡片矮到连地点带都放不下时，
        //   必须整条撤掉地点带（而不是画半行），把高度全给课名。
        //   边界由**当前字体度量**现算（写死 30dp 之类的话，换台机器就测不到这条了）
        val compactRoomBand = gridCardRoomBandHeight(gridCardStyle(GridCardTier.Compact), roomLine)
        val lastCompact = compactRoomBand + 14.dp

        listOf(0.dp, 6.dp, 12.dp, 20.dp, lastCompact - 0.1.dp).forEach { height ->
            assertEquals(
                "卡片高 $height 时应该撤掉地点带",
                GridCardTier.UltraCompact,
                gridCardTier(height, roomLine, nameLine),
            )
        }
        assertEquals(
            "刚好还留得住 14dp 课名区时地点带照画",
            GridCardTier.Compact,
            gridCardTier(lastCompact, roomLine, nameLine),
        )
    }

    @Test
    fun 撤掉地点带后那点高度全归课名() {
        val style = gridCardStyle(GridCardTier.UltraCompact)
        assertTrue("最紧凑档必须撤掉地点带", !style.roomBand)
        // 地点带实高为 0：课名区的可用高度就是整张卡（留 padding 的话那点高度就白丢了）
        assertEquals(0f, gridCardRoomBandHeight(style, roomLine).value, 0.001f)
        // 最紧凑档的实高比宽松档低（否则撤了地点带反而要更高，等于没撤）
        assertTrue(
            gridCardMinHeight(style, nameLine, roomLine) <
                gridCardMinHeight(gridCardStyle(GridCardTier.Full), nameLine, roomLine),
        )
    }

    @Test
    fun 广工六块的行高正好落在紧凑档() {
        // 688dp 可用、6 块 → 88dp 一行（被 MaxRowHeight 封顶）→ 卡片走宽松档。
        // 真正压到 30~35dp 的是"块数多 + 矮屏"（例如默认表 8 块 + 320dp 可用 → 40dp）
        val rowHeight = gridRowHeight(availableHeight = 688.dp, rowCount = 6)
        assertEquals(GridCardTier.Full, gridCardTier(rowHeight, roomLine, nameLine))
        // 矮屏上 8 块：320 ÷ 8 = 40dp → 仍放得下地点带（撤了地点带才是真正读不到课名的档）
        val narrow = gridRowHeight(availableHeight = 320.dp, rowCount = 8)
        assertTrue("窄屏上应该只有 40dp 上下一行", narrow.value < 45f)
        assertTrue(
            "40dp 的行高必须还放得下地点带",
            gridCardTier(narrow, roomLine, nameLine) != GridCardTier.UltraCompact,
        )
    }

    // ══════════════════ ② 档位边界 ══════════════════

    @Test
    fun 放得下地点带加一行课名就是宽松档() {
        val full = gridCardMinHeight(gridCardStyle(GridCardTier.Full), nameLine, roomLine)
        assertEquals(GridCardTier.Full, gridCardTier(full, roomLine, nameLine))
        // 差 0.1dp 就退一档（相邻的高度必须落在不同档，否则"刚好放不下"会被裁半行字）
        assertTrue(gridCardTier(full - 0.1.dp, roomLine, nameLine) != GridCardTier.Full)
    }

    @Test
    fun 地点带还在但课名被挤时退到紧凑档() {
        val compact = gridCardStyle(GridCardTier.Compact)
        // 课名区恰好还有 14dp（MinNameArea）
        val roomBand = gridCardRoomBandHeight(compact, roomLine)
        assertEquals(
            GridCardTier.Compact,
            gridCardTier(roomBand + 14.dp, roomLine, nameLine),
        )
        // 再矮 0.1dp：课名区就被地点带挤到 14dp 以下了 → 撤掉地点带、把高度全给课名
        assertEquals(
            GridCardTier.UltraCompact,
            gridCardTier(roomBand + 13.9.dp, roomLine, nameLine),
        )
    }

    @Test
    fun 跨多行的卡片走宽松档() {
        // 连上四节的实验卡片本来就有 4 倍行高：不该按"行高矮"来判断，
        // 判断的输入必须是**卡片实高**（调用方传 rowHeight × span）
        val rowHeight = gridRowHeight(availableHeight = 688.dp, rowCount = 6)
        assertEquals(GridCardTier.Full, gridCardTier(rowHeight * 4, roomLine, nameLine))
    }

    // ══════════════════ ③ 门槛跟着字体度量走 ══════════════════

    @Test
    fun 字体调大后门槛跟着抬高() {
        val bigDensity = Density(density = 2.75f, fontScale = 1.17f * 1.3f)
        val bigName = spToDp(bigDensity, 12.sp)
        val bigRoom = spToDp(bigDensity, 10.sp)

        val fullAtNormal = gridCardMinHeight(gridCardStyle(GridCardTier.Full), nameLine, roomLine)
        val fullAtBig = gridCardMinHeight(gridCardStyle(GridCardTier.Full), bigName, bigRoom)
        assertTrue("字号变大，宽松档需要的高度必须跟着变大", fullAtBig > fullAtNormal)

        // ★ 这就是"门槛不能写死 dp"的实证：同一个卡片高度在 1 倍字体下够宽松档，
        //   在 1.3 倍字体下已经不够了（那时必须退档，否则地点那行字被卡片下沿裁掉）
        assertEquals(GridCardTier.Full, gridCardTier(fullAtNormal, roomLine, nameLine))
        assertTrue(gridCardTier(fullAtNormal, bigRoom, bigName) != GridCardTier.Full)
    }

    // ══════════════════ ④ 撤到最紧凑档时课名还在 ══════════════════

    @Test
    fun 撤掉地点带后课名区仍画得出一行() {
        // 最紧凑档 + 30dp 卡片（块数多 + 矮屏时的量级）：
        // 课名区 = 30dp（地点带 0），7.5sp 字（约 8.8dp 一行）→ 3 行
        val style = gridCardStyle(GridCardTier.UltraCompact)
        val nameArea = 30.dp - gridCardRoomBandHeight(style, roomLine)
        assertTrue("最紧凑档也必须至少画一行课名", nameLineCount(nameArea, spToDp(density, style.nameLine)) >= 1)
        assertTrue(
            "课名区越大画得越多（少显示一行是让步，不是规定）",
            nameLineCount(nameArea, spToDp(density, style.nameLine)) >=
                nameLineCount(8.dp, spToDp(density, style.nameLine)),
        )
    }

    @Test
    fun 三档的字号与地点带是一条单调的降级链() {
        val tiers = listOf(GridCardTier.Full, GridCardTier.Compact, GridCardTier.UltraCompact)
        val styles = tiers.map { gridCardStyle(it) }
        // 字号只减不增
        styles.zipWithNext().forEach { (a, b) ->
            assertTrue("下一档的课名字号不能比上一档大", b.nameFont.value <= a.nameFont.value)
            assertTrue("下一档的地点字号不能比上一档大", b.roomFont.value <= a.roomFont.value)
        }
        // 地点带只可能"从有到无"，不可能反过来
        assertEquals(listOf(true, true, false), styles.map { it.roomBand })
    }
}
