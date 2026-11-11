package uk.co.barcodealarm

import java.time.*

data class Alarm(val id: Int, val hour: Int, val minute: Int, val code: String, val format: String,
 val sound: String = "", val enabled: Boolean = false, val due: Long = 0, val ringing: Boolean = false,
 val started: Long = -1, val correct: Int = 0, val barcodeId: Int = 0)
object Rules {
 fun next(hour: Int, minute: Int, now: ZonedDateTime): Long {
  var next = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
  if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
  return next.toInstant().toEpochMilli()
 }
 fun matches(value: String, format: String, other: String, otherFormat: String): Boolean {
  fun canonical(v: String, f: String) = if (f == "EAN_13" && v.length == 13 && v.startsWith("0")) "UPC_A" to v.drop(1) else f to v
  return canonical(value, format) == canonical(other, otherFormat)
 }
 fun shouldTrigger(a: Alarm, due: Long) = a.enabled && !a.ringing && a.due == due
 fun ready(a: Alarm, elapsed: Long) = a.started >= 0 && elapsed >= a.started && elapsed-a.started >= 300_000 && a.correct >= 15
 fun dismissed(a: Alarm) = a.copy(enabled=false, due=0, ringing=false, started=-1, correct=0)
}
data class Question(val text: String, val answer: Int, val choices: List<Int>) {
 companion object {
  fun create(): Question {
   val a = (3..19).random(); val b = (2..12).random()
   val (text, answer) = when ((0..3).random()) {
    0 -> "$a + $b" to a+b
    1 -> "${a+b} − $b" to a
    2 -> "$a × $b" to a*b
    else -> "${a*b} ÷ $b" to a
   }
   val choices = mutableSetOf(answer)
   while (choices.size < 4) choices.add((answer-12..answer+12).random())
   return Question(text, answer, choices.shuffled())
  }
 }
}
