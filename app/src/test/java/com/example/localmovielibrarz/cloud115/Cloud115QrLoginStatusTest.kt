package com.example.localmovielibrarz.cloud115

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 115 二维码状态映射的回归。
 *
 * 线上 bug：旧实现要求响应里必须有 `data.status`，否则抛「115 二维码状态响应缺少 status」。
 * 但两个状态端点在「未扫码」阶段都不返回 status（2026-11 实测）：
 * - 长轮询 `/get/status/`：挂起约 30s 后返回 `{"state":1,"code":0,"data":{}}`；
 * - 短轮询 `/api/1.0/web/1.0/status/`：立即返回 `{"state":false,"code":90038,"message":"请扫描二维码"}`。
 * 结果是一进登录页就报错，扫码流程根本走不到。
 *
 * 下面的用例按真实响应字段取值，逐条锁定映射行为。
 */
class Cloud115QrLoginStatusTest {

    @Test
    fun longPollWaitingWithoutStatusFieldIsWaiting() {
        // {"state":1,"code":0,"message":"","data":{}}
        assertEquals(Cloud115QrLoginStatus.Waiting, resolveQrLoginStatus(stateOk = true, code = 0, statusText = ""))
    }

    @Test
    fun shortPollWaitingWithCode90038IsWaiting() {
        // {"state":false,"message":"请扫描二维码","code":90038}
        assertEquals(Cloud115QrLoginStatus.Waiting, resolveQrLoginStatus(stateOk = false, code = 90038, statusText = ""))
    }

    @Test
    fun shortPollExpiredWithCode90033IsExpired() {
        // {"state":false,"message":"二维码已过期 请刷新重新获取二维码","code":90033}
        assertEquals(Cloud115QrLoginStatus.Expired, resolveQrLoginStatus(stateOk = false, code = 90033, statusText = ""))
    }

    @Test
    fun keyInvalidIsUnrecognised() {
        // {"state":0,"code":40199002,"message":"key invalid","data":{}} —— 交给调用方报错，不能当成等待
        assertNull(resolveQrLoginStatus(stateOk = false, code = 40199002, statusText = ""))
        assertNull(resolveQrLoginStatus(stateOk = true, code = 40199002, statusText = ""))
    }

    @Test
    fun numericStatusCoversWholeLifecycle() {
        assertEquals(Cloud115QrLoginStatus.Waiting, resolveQrLoginStatus(false, 0, "0"))
        assertEquals(Cloud115QrLoginStatus.Scanned, resolveQrLoginStatus(false, 0, "1"))
        assertEquals(Cloud115QrLoginStatus.Confirmed, resolveQrLoginStatus(false, 0, "2"))
        assertEquals(Cloud115QrLoginStatus.Expired, resolveQrLoginStatus(false, 0, "-1"))
        assertEquals(Cloud115QrLoginStatus.Canceled, resolveQrLoginStatus(false, 0, "-2"))
    }

    @Test
    fun numericStatusWinsOverStatusCodes() {
        // data.status 一旦出现就以它为准，避免被顶层 code 带偏
        assertEquals(Cloud115QrLoginStatus.Confirmed, resolveQrLoginStatus(stateOk = false, code = 90038, statusText = "2"))
        assertEquals(Cloud115QrLoginStatus.Expired, resolveQrLoginStatus(stateOk = true, code = 0, statusText = "-1"))
    }

    @Test
    fun textualStatusFallback() {
        assertEquals(Cloud115QrLoginStatus.Scanned, resolveQrLoginStatus(false, 0, "scanned"))
        assertEquals(Cloud115QrLoginStatus.Confirmed, resolveQrLoginStatus(false, 0, "login success"))
        assertEquals(Cloud115QrLoginStatus.Confirmed, resolveQrLoginStatus(false, 0, "confirmed"))
        assertEquals(Cloud115QrLoginStatus.Expired, resolveQrLoginStatus(false, 0, "expired"))
        assertEquals(Cloud115QrLoginStatus.Canceled, resolveQrLoginStatus(false, 0, "canceled"))
    }

    @Test
    fun unknownShapeIsUnrecognised() {
        assertNull(resolveQrLoginStatus(stateOk = false, code = 0, statusText = ""))
        assertNull(resolveQrLoginStatus(stateOk = false, code = 123, statusText = "whatever"))
        assertNull(resolveQrLoginStatus(stateOk = false, code = 0, statusText = "7"))
    }
}
