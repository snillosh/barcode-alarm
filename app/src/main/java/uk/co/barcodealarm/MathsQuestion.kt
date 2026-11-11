package uk.co.barcodealarm

/** The question and all four answer choices stay together as one immutable value. */
data class MathsQuestion(val text: String, val answer: Int, val choices: List<Int>) {
    // A companion object groups factory methods, much like static methods in C#.
    companion object {
        fun create(): MathsQuestion {
            val firstNumber = (3..19).random()
            val secondNumber = (2..12).random()
            val questionText: String
            val answer: Int

            // Kotlin's `when` is the equivalent of a C# switch.
            when ((0..3).random()) {
                0 -> {
                    questionText = "$firstNumber + $secondNumber"
                    answer = firstNumber + secondNumber
                }
                1 -> {
                    questionText = "${firstNumber + secondNumber} − $secondNumber"
                    answer = firstNumber
                }
                2 -> {
                    questionText = "$firstNumber × $secondNumber"
                    answer = firstNumber * secondNumber
                }
                else -> {
                    questionText = "${firstNumber * secondNumber} ÷ $secondNumber"
                    answer = firstNumber
                }
            }

            // A set prevents duplicate choices. Include the correct answer before adding
            // distractors.
            val possibleAnswers = mutableSetOf(answer)
            while (possibleAnswers.size < 4) {
                possibleAnswers.add((answer - 12..answer + 12).random())
            }
            return MathsQuestion(questionText, answer, possibleAnswers.shuffled())
        }
    }
}
