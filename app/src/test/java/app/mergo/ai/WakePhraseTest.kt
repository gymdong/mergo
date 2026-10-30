package app.mergo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePhraseTest {
    @Test
    fun `호출어 뒤에 이어 말한 내용을 꺼낸다`() {
        assertEquals(WakeHit("소리가안나"), WakePhrase.find("머고야소리가안나", "머고야"))
        assertEquals(WakeHit("잘못눌렀어"), WakePhrase.find("머고야 잘못 눌렀어.", "머고야"))
    }

    @Test
    fun `호출어만 부르면 이어진 말이 없다`() {
        assertEquals(WakeHit(null), WakePhrase.find("머고야.", "머고야"))
    }

    @Test
    fun `비슷하게 받아써진 호출어도 알아듣는다`() {
        assertEquals(WakeHit("아까그거"), WakePhrase.find("뭐고야아까그거", "머고야"))
    }

    @Test
    fun `받아쓰기가 바꿔 쓴 호출어도 맨 앞이면 인정한다`() {
        assertEquals(WakeHit("소리가안나"), WakePhrase.find("먹어야 소리가 안 나.", "머고야"))
        assertEquals(WakeHit(null), WakePhrase.find("머거야", "머고야"))
    }

    @Test
    fun `문장 중간의 비슷한 말은 부른 것으로 보지 않는다`() {
        assertNull(WakePhrase.find("이렇게 많이 먹어야 힘이 나지", "머고야"))
        assertNull(WakePhrase.find("많이 먹어야 힘이 나지.", "머고야"))
        assertNull(WakePhrase.find("앱을 부르는 머고야라는 말이 나올 수 있다", "머고야"))
    }

    @Test
    fun `호출어 앞의 말은 버린다`() {
        assertEquals(WakeHit("광고안꺼져"), WakePhrase.find("어머고야광고안꺼져", "머고야"))
    }

    @Test
    fun `호출어가 없으면 반응하지 않는다`() {
        assertNull(WakePhrase.find("오늘날씨가정말좋네요", "머고야"))
        assertNull(WakePhrase.find("이거뭐고", "머고야"))
        assertNull(WakePhrase.find("", "머고야"))
    }

    @Test
    fun `설정에서 바꾼 호출어를 따른다`() {
        assertEquals(WakeHit("소리가안나"), WakePhrase.find("춘식아소리가안나", "춘식아"))
        assertEquals(WakeHit(null), WakePhrase.find("춘식아", "춘식 아"))
        assertNull(WakePhrase.find("머고야소리가안나", "춘식아"))
    }
}
