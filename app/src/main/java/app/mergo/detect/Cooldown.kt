package app.mergo.detect

/**
 * 거절된 제안을 반복하지 않게 막는다.
 * 같은 규칙·같은 대상은 다시 제안하지 않고, 그 규칙 전체는 5분 동안 쉰다.
 */
class Cooldown(private val ruleRestMs: Long = 5 * 60_000L) {
    private val rejectedTargets = mutableSetOf<Pair<RuleId, String>>()
    private val ruleRestUntil = mutableMapOf<RuleId, Long>()

    @Synchronized
    fun reject(rule: RuleId, target: String, now: Long) {
        rejectedTargets += rule to target
        ruleRestUntil[rule] = now + ruleRestMs
    }

    @Synchronized
    fun isBlocked(rule: RuleId, target: String, now: Long): Boolean =
        (rule to target) in rejectedTargets || now < (ruleRestUntil[rule] ?: Long.MIN_VALUE)
}
