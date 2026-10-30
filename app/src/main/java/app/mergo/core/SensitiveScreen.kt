package app.mergo.core

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

    /** 띄어쓰기를 뺀 화면 글자에서 찾는다. 진짜인지 가짜인지는 가리지 않는다. */
    private val WORDS = listOf(
        "카드번호", "비밀번호", "인증번호", "주민등록", "계좌번호", "보안카드", "결제하기", "cardnumber", "password",
    )

    fun matches(pkg: String?, texts: List<String>): Boolean {
        if (pkg in PKGS) return true
        val screen = texts.joinToString("") { it.lowercase().filterNot(Char::isWhitespace) }
        return WORDS.any { it in screen }
    }
}
