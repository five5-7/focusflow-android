package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入页段落顺序的回归测试（纯 JVM，不渲染、不依赖测试基建）。
 *
 * 背景：真机反馈"选学期太靠下"，修法是把学期卡移到凭据卡之前。这类"顺序"要求原先只能靠人眼在真机上确认。
 * 曾尝试 Compose UI 测试，但它在**全量套件**里 4 项全部 60 秒超时（`Compose did not get idle`）；
 * 手动测试时钟只救下"该测试 + 全部 Robolectric 类"这一组合，全量仍挂，**根因未定位**。
 * 因此把**顺序本身**抽成 [ZJU_IMPORT_SECTION_ORDER] 数据，由界面按序遍历、由本测试断言。
 *
 * 覆盖边界（不要高估它）：
 * - 断言的是**声明的完整顺序**，不是像素位置；"首屏内"只是结构代理，像素可见性无运行期保证。
 * - 不覆盖"运行中返回键可点"：那项目前没有自动化守卫，靠代码注释与真机验收承接。
 */
class ZjuImportSectionOrderTest {

    @Test
    fun `the declared order is exactly the expected sequence`() {
        // 一条锁死全部位置。只断言"相对顺序"会漏掉页头/失败卡/边界说明的位置：
        // 复核给过的反例是把列表改成 BOUNDARY_NOTE…HEADER，仅靠相对断言会全绿。
        assertEquals(
            listOf(
                ZjuImportSection.HEADER,
                ZjuImportSection.SEMESTER_OPTIONS,
                ZjuImportSection.CREDENTIALS,
                ZjuImportSection.PROGRESS,
                ZjuImportSection.FAILURE,
                ZjuImportSection.BOUNDARY_NOTE
            ),
            ZJU_IMPORT_SECTION_ORDER
        )
    }

    @Test
    fun `the header stays at the top`() {
        assertEquals(ZjuImportSection.HEADER, ZJU_IMPORT_SECTION_ORDER.first())
    }

    @Test
    fun `semester options come before the credential card`() {
        val semester = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.SEMESTER_OPTIONS)
        val credentials = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.CREDENTIALS)

        assertTrue("semester=$semester credentials=$credentials", semester >= 0 && credentials >= 0)
        assertTrue("semester selection must precede the credential card", semester < credentials)
    }

    @Test
    fun `only the header precedes the semester options`() {
        // "落在首屏内"在结构上的表达：前面只允许有一个段落，而且它必须就是页面头部。
        assertEquals(1, ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.SEMESTER_OPTIONS))
        assertEquals(ZjuImportSection.HEADER, ZJU_IMPORT_SECTION_ORDER.first())
    }

    @Test
    fun `every section appears exactly once in the declared order`() {
        assertEquals(
            "the declared order must cover every section exactly once",
            ZjuImportSection.entries.toSet(),
            ZJU_IMPORT_SECTION_ORDER.toSet()
        )
        assertEquals(ZjuImportSection.entries.size, ZJU_IMPORT_SECTION_ORDER.size)
    }

    @Test
    fun `the progress card keeps its place after the credential card`() {
        val credentials = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.CREDENTIALS)
        val progress = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.PROGRESS)

        assertTrue("credentials=$credentials progress=$progress", credentials >= 0 && progress >= 0)
        assertTrue("progress must stay after the credential card", credentials < progress)
    }

    @Test
    fun `the failure card stays before the boundary note`() {
        val failure = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.FAILURE)
        val note = ZJU_IMPORT_SECTION_ORDER.indexOf(ZjuImportSection.BOUNDARY_NOTE)

        assertTrue("failure=$failure note=$note", failure >= 0 && note >= 0)
        assertTrue("failure card must stay above the boundary note", failure < note)
    }
}
