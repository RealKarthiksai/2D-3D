package dev.capturekit.sample

import dev.capturekit.sample.ScanUi.ScanStage
import dev.capturekit.upload.UploadState
import dev.capturekit.upload.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanStageTest {
    @Test fun everyUploadStateMapsToAStage() {
        val expected = mapOf(
            UploadState.NONE to ScanStage.ON_PHONE,
            UploadState.WAITING to ScanStage.WAITING_NETWORK,
            UploadState.UPLOADING to ScanStage.UPLOADING,
            UploadState.RETRYING to ScanStage.UPLOADING,
            UploadState.VERIFIED to ScanStage.UPLOADED,
            UploadState.FAILED to ScanStage.FAILED,
        )
        for (s in UploadState.values()) assertEquals(s.name, expected.getValue(s), ScanUi.stageOf(UploadStatus(s)))
    }

    @Test fun stageWordsNeverShowPercentOrCodes() {
        for (s in ScanStage.values()) for (wifi in listOf(true, false)) {
            val t = ScanUi.stageText(s, wifi)
            assertFalse(t, t.contains("%") || t.contains("status", ignoreCase = true) || Regex("""\b[A-Z]{2,}\b""").containsMatchIn(t.replace("3D", "")))
        }
    }

    @Test fun failedAndDoneCarryStatusKinds() {
        assertEquals(UiKit.Kind.ERROR, ScanUi.stageKind(ScanStage.FAILED))
        assertEquals(UiKit.Kind.OK, ScanUi.stageKind(ScanStage.DONE))
        assertEquals(UiKit.Kind.OK, ScanUi.stageKind(ScanStage.UPLOADED))
    }
}
