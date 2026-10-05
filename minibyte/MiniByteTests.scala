/** Native arithmetic appears only here, as an independent test oracle. */
object MiniByteTests {
  import MiniByte._
  def main(args: Array[String]): Unit = {
    var checks = 0
    val start = System.nanoTime()
    def check(condition: Boolean, label: String): Unit = {
      checks += 1
      if (!condition) throw new AssertionError(label)
    }
    def rejected(text: String): Boolean = try { parseNumber(text); false }
      catch { case _: IllegalArgumentException => true }
    val session = new Session
    for (n <- 0 until 256) check(decodeWord(session.normalize(Core.word(n)))._1 == n, "encoding " + n)
    check(session.compute(6, "+", 7).line ==
      "λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s4 (s5 (s7 x)) 0b1101 13", "requested output")
    check(parseNumber("0b110") == 6 && parseNumber("0B111") == 7, "binary input")
    check(parseNumber("0006") == 6 && parseNumber("255") == 255, "decimal input")
    for (text <- Vector("256", "0b100000000", "0b102", "0b", "0x110", "-1", "1.5", "", "99999999999999999999"))
      check(rejected(text), "reject " + text)
    val inputs = if (args.contains("--exhaustive")) (0 until 256).toVector else
      Vector(0, 1, 2, 3, 6, 7, 15, 16, 31, 63, 64, 127, 128, 200, 254, 255)
    for (op <- Vector("+", "-", "*")) {
      var count = 0
      for (a <- inputs; b <- inputs) {
        val exact = op match { case "+" => a + b; case "-" => a - b; case "*" => a * b }
        val actual = session.compute(a, op, b)
        check(actual.byte == (exact & 255) && actual.fault == (exact < 0 || exact > 255),
          a + " " + op + " " + b + ": " + actual)
        count += 1
      }
      println(op + ": " + count + " input pairs; byte and unsigned fault passed.")
    }
    println("PASS: " + checks + " checks in " + ("%.2f".format((System.nanoTime() - start) / 1e9)) + " seconds.")
  }
}
