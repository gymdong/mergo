package app.mergo.core

import app.mergo.model.RecipeIds
import org.junit.Assert.assertEquals
import org.junit.Test

class TextsTest {
    @Test
    fun `이름으로 앱을 열 때는 그 앱 이름으로 말한다`() {
        assertEquals("유튜브를 열고 있어요", Texts.of(RecipeIds.OPEN_PREVIOUS_APP, mapOf("app" to "youtube")).progress)
        assertEquals("보던 앱으로 돌아가고 있어요", Texts.of(RecipeIds.OPEN_PREVIOUS_APP, mapOf("pkg" to "com.kakao.talk")).progress)
    }
}
