package app.zaid.flow.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiQuizTest {
    @Test
    fun `a clean quiz reply is read`() {
        val q =
            parseQuiz(
                """{"feedback":"","question":"What is 2+2?","options":["3","4","5","6"],"correct":1,"explanation":"Basic sums."}""",
                1,
            )
        assertNotNull(q)
        assertEquals("What is 2+2?", q!!.question)
        assertEquals(listOf("3", "4", "5", "6"), q.options)
        assertEquals(1, q.correctIndex)
        assertEquals(1, q.number)
    }

    @Test
    fun `fences, letters on options and a letter answer are tolerated`() {
        val q =
            parseQuiz(
                "```json\n{\"question\":\"Pick\",\"options\":[\"A) one\",\"B) two\",\"C) three\"],\"correct\":\"C\"}\n```",
                3,
            )
        assertEquals(listOf("one", "two", "three"), q!!.options)
        assertEquals(2, q.correctIndex)
    }

    @Test
    fun `plain text and unusable questions are not quizzes`() {
        assertNull(parseQuiz("Here is some text.", 1))
        assertNull(parseQuiz("""{"question":"Q","options":["only"],"correct":0}""", 1))
        assertNull(parseQuiz("""{"question":"Q","options":["a","b"],"correct":5}""", 1))
        assertNull(parseQuiz("""{"options":["a","b"],"correct":0}""", 1))
    }
}
