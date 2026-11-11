package uk.co.barcodealarm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RulesTest {
 private val a=Alarm(1,7,0,"123","QR_CODE",enabled=true,due=42,ringing=true,started=1000,correct=15)
 private fun next(now: String)=Instant.ofEpochMilli(Rules.next(7,0,ZonedDateTime.parse(now))).toString()
 @Test fun consumesDuplicateDelivery() {
  val scheduled=a.copy(ringing=false)
  assertTrue(Rules.shouldTrigger(scheduled,42))
  assertFalse(Rules.shouldTrigger(scheduled.copy(enabled=false,due=0,ringing=true),42))
  assertFalse(Rules.shouldTrigger(scheduled,41))
  assertFalse(Rules.shouldTrigger(Rules.dismissed(scheduled),42))
 }
 @Test fun laterToday() { assertEquals("2026-11-11T07:00:00Z",next("2026-11-11T06:00:00Z[Europe/London]")) }
 @Test fun tomorrow() { assertEquals("2026-11-12T07:00:00Z",next("2026-11-11T22:00:00Z[Europe/London]")) }
 @Test fun exactMinuteIsTomorrow() { assertEquals("2026-11-12T07:00:00Z",next("2026-11-11T07:00:00Z[Europe/London]")) }
 @Test fun dstGapMovesForward() { assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(),Rules.next(1,30,ZonedDateTime.parse("2026-03-29T00:00:00Z[Europe/London]"))) }
 @Test fun dismissalDisablesAndConsumes() { val d=Rules.dismissed(a);assertFalse(d.enabled);assertFalse(d.ringing);assertEquals(0L,d.due);assertEquals(a.code,d.code) }
 @Test fun identicalMatches() { assertTrue(Rules.matches("abc","QR_CODE","abc","QR_CODE")) }
 @Test fun wrongCodeRejected() { assertFalse(Rules.matches("abc","QR_CODE","abd","QR_CODE")) }
 @Test fun differentFormatRejected() { assertFalse(Rules.matches("123","QR_CODE","123","CODE_128")) }
 @Test fun equivalentUpc() { assertTrue(Rules.matches("0123456789012","EAN_13","123456789012","UPC_A")) }
 @Test fun timeAloneInsufficient() { assertFalse(Rules.ready(a.copy(correct=14),301000)) }
 @Test fun answersAloneInsufficient() { assertFalse(Rules.ready(a,300999)) }
 @Test fun bothRequirements() { assertTrue(Rules.ready(a,301000)) }
 @Test fun noStartCannotDismiss() { assertFalse(Rules.ready(a.copy(started=-1),900000)) }
 @Test fun monotonicRollbackCannotDismiss() { assertFalse(Rules.ready(a,0)) }
 @Test fun questionsHaveFourUniqueAnswers() { repeat(1000) { val q=Question.create();assertEquals(4,q.choices.toSet().size);assertTrue(q.answer in q.choices) } }
}
