package app.mergo.core

/** 도와줘를 부른 순간의 앞 화면. 이벤트로 모은 글자는 페이지가 늦게 뜨거나 앱 안에서 넘어가면 낡아서, 그때 직접 읽는다. */
data class LiveScreen(val pkg: String?, val texts: List<String>)

/**
 * 머고가 대신 누르면 안 되는 화면. 설치·결제·권한 화면과, 카드번호·비밀번호 같은 개인정보를 묻는 화면이다.
 * 여기서 도와줘를 부르면 아무것도 누르지 않고 빠져나오는 법만 안내한다(페르소나 P3-3·P4-3).
 */
object SensitiveScreen {
    private val PKGS = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /**
     * 띄어쓰기를 뺀 화면 글자에서 찾는다. 진짜인지 가짜인지는 가리지 않는다.
     * "비밀번호"·"인증번호"만으로는 와이파이 설정이나 카톡 대화에도 나와서, 입력을 요구하는 꼴만 둔다.
     */
    private val WORDS = listOf(
        "카드번호", "주민등록번호", "계좌번호", "보안카드", "결제하기", "비밀번호입력", "인증번호입력", "cardnumber",
    )

    fun matches(screen: LiveScreen): Boolean {
        if (screen.pkg in PKGS) return true
        val text = screen.texts.joinToString("") { it.lowercase().filterNot(Char::isWhitespace) }
        return WORDS.any { it in text }
    }
}
