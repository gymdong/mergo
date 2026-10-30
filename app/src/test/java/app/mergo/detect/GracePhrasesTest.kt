package app.mergo.detect

import app.mergo.model.GracePhrases
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GracePhrasesTest {
    @Test
    fun `호출어가 붙거나 띄어쓰기가 달라도 알아듣는다`() {
        for (h in listOf("머고야 잘못 눌렀다", "잘못눌렀어", "머고야 아까 그거", "돌려줘", "뭐고 이거", "이거 아이다")) {
            assertTrue(h, GracePhrases.matches(h))
        }
    }

    @Test
    fun `영상 속 일상 문장에는 반응하지 않는다`() {
        for (h in listOf("오늘 날씨가 참 좋네요", "다음 소식입니다", "구독과 좋아요 부탁드려요")) {
            assertFalse(h, GracePhrases.matches(h))
        }
    }
}
